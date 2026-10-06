# /// script
# requires-python = ">=3.10"
# dependencies = ["fido2==2.2.1"]
# ///
import struct
import subprocess

from fido2.attestation import Attestation
from fido2.client import DefaultClientDataCollector, Fido2Client
from fido2.ctap import CtapDevice, CtapError
from fido2.ctap2 import ClientPin, CredentialManagement, Ctap2
from fido2.ctap2.pin import PinProtocolV1, PinProtocolV2
from fido2.hid import CAPABILITY, CTAPHID
from fido2.server import Fido2Server

ERR = CtapError.ERR
RP = {"id": "example.com", "name": "Example"}
ES256 = [{"type": "public-key", "alg": -7}]
HASH = bytes(32)


class Harness(CtapDevice):
    capabilities = CAPABILITY.CBOR

    def __init__(self):
        self.process = subprocess.Popen(
            ["java", "--enable-native-access=ALL-UNNAMED", "us.z1x.fidont.HarnessKt"],
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
        )

    def call(self, cmd, data=b"", event=None, on_keepalive=None):
        assert cmd == CTAPHID.CBOR
        self.process.stdin.write(struct.pack(">H", len(data)) + data)
        self.process.stdin.flush()
        (size,) = struct.unpack(">H", self.process.stdout.read(2))
        return self.process.stdout.read(size)

    def close(self):
        self.process.kill()

    @classmethod
    def list_devices(cls):
        yield cls()


def rejects(code, call, *args, **kwargs):
    try:
        call(*args, **kwargs)
    except CtapError as error:
        assert error.code == code, error
    else:
        raise AssertionError(f"expected {code!r}")


def verify_attestation(attestation, client_data_hash):
    Attestation.for_type(attestation.fmt)().verify(
        attestation.att_stmt, attestation.auth_data, client_data_hash
    )


def register(name):
    user = {"id": name.encode(), "name": name, "displayName": name.title()}
    options, state = server.register_begin(user, resident_key_requirement="required")
    response = client.make_credential(
        {**options["publicKey"], "extensions": {"prf": {}}}
    )
    assert response.client_extension_results.prf.enabled
    return server.register_complete(state, response).credential_data


def authenticate(credentials, salt, discoverable=False):
    options, state = server.authenticate_begin([] if discoverable else credentials)
    extensions = {"prf": {"eval": {"first": salt}}}
    response = client.get_assertion(
        {**options["publicKey"], "extensions": extensions}
    ).get_response(0)
    server.authenticate_complete(state, credentials, response)
    return response.client_extension_results.prf.results.first


device = Harness()
ctap = Ctap2(device)
client = Fido2Client(device, DefaultClientDataCollector("https://example.com"))
server = Fido2Server(RP, attestation="direct", verify_attestation=verify_attestation)

print("getInfo")
assert ctap.info.versions == ["FIDO_2_0", "FIDO_2_1"]
assert ctap.info.options["rk"] and ctap.info.options["uv"]
assert ctap.info.algorithms == ES256

print("makeCredential")
alice = register("alice")
assert alice.aaguid == ctap.info.aaguid
bob = register("bob")
excluded = [{"type": "public-key", "id": alice.credential_id}]
rejects(
    ERR.CREDENTIAL_EXCLUDED,
    ctap.make_credential,
    HASH,
    RP,
    {"id": b"carol"},
    ES256,
    exclude_list=excluded,
)
rejects(
    ERR.UNSUPPORTED_ALGORITHM,
    ctap.make_credential,
    HASH,
    RP,
    {"id": b"carol"},
    [{"type": "public-key", "alg": -8}],
)
rejects(ERR.MISSING_PARAMETER, ctap.make_credential, HASH, RP, {}, ES256)

print("getAssertion")
first = authenticate([alice], b"one")
assert first == authenticate([alice], b"one")
assert first != authenticate([alice], b"two")
assert first != authenticate([bob], b"one")
assert first == authenticate([alice, bob], b"one", discoverable=True)
rejects(ERR.NO_CREDENTIALS, ctap.get_assertion, "other.example", HASH)
rejects(
    ERR.PIN_AUTH_INVALID,
    ctap.get_assertion,
    RP["id"],
    HASH,
    pin_uv_param=bytes(32),
    pin_uv_protocol=2,
)

print("credentialManagement")
for protocol in PinProtocolV1(), PinProtocolV2():
    token = ClientPin(ctap, protocol).get_uv_token(ClientPin.PERMISSION.CREDENTIAL_MGMT)
    manager = CredentialManagement(ctap, protocol, token)
    assert manager.get_metadata()[1] == 2
    (rp,) = manager.enumerate_rps()
    assert rp[3]["id"] == RP["id"]
    stored = manager.enumerate_creds(rp[4])
    assert [entry[6]["name"] for entry in stored] == ["alice", "bob"]
    assert stored[0][8] == alice.public_key
    manager.update_user_info(
        stored[1][7], {"id": b"bob", "name": "bob", "displayName": "Robert"}
    )
    assert manager.enumerate_creds(rp[4])[1][6]["displayName"] == "Robert"
rejects(
    ERR.PIN_AUTH_INVALID,
    CredentialManagement(ctap, protocol, bytes(32)).get_metadata,
)
manager.delete_cred(stored[1][7])
assert manager.get_metadata()[1] == 1
rejects(
    ERR.NO_CREDENTIALS,
    ctap.get_assertion,
    RP["id"],
    HASH,
    [{"type": "public-key", "id": bob.credential_id}],
)

print("malformed requests")
assert device.call(CTAPHID.CBOR, b"\x7f") == bytes([ERR.INVALID_COMMAND])
assert device.call(CTAPHID.CBOR, b"\x01\xff") == bytes([ERR.INVALID_CBOR])
assert device.call(CTAPHID.CBOR, b"\x01") == bytes([ERR.MISSING_PARAMETER])

device.close()
print("passed")
