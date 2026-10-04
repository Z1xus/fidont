package main

import (
	"context"
	"crypto/sha256"
	"encoding/binary"
	"flag"
	"fmt"
	"log"
	"net/http"
	"strings"
	"sync"
	"time"

	"github.com/coder/websocket"
)

const (
	lifetime = 2 * time.Minute
	// a real session moves a few kilobytes, more than this is someone using the relay as a tunnel
	budget = 256 << 10
	// bounds the memory that idle tunnels can hold
	maxTunnels = 4096
)

var accept = &websocket.AcceptOptions{Subprotocols: []string{"fido.cable"}}

type tunnel struct {
	phone *websocket.Conn
	done  chan struct{}
}

type relay struct {
	mu      sync.Mutex
	tunnels map[string]*tunnel
}

func (r *relay) open(w http.ResponseWriter, req *http.Request) {
	r.mu.Lock()
	full := len(r.tunnels) >= maxTunnels
	r.mu.Unlock()
	if full {
		http.Error(w, "busy", http.StatusServiceUnavailable)
		return
	}

	id := strings.ToLower(req.PathValue("tunnel"))
	// one instance serves every tunnel, so the routing id carries nothing
	w.Header().Set("X-caBLE-Routing-ID", "000000")
	phone, err := websocket.Accept(w, req, accept)
	if err != nil {
		return
	}
	defer phone.CloseNow()

	t := &tunnel{phone: phone, done: make(chan struct{})}
	r.mu.Lock()
	r.tunnels[id] = t
	r.mu.Unlock()

	select {
	case <-t.done:
	case <-time.After(lifetime):
	}

	r.mu.Lock()
	if r.tunnels[id] == t {
		delete(r.tunnels, id)
	}
	r.mu.Unlock()
}

func (r *relay) connect(w http.ResponseWriter, req *http.Request) {
	id := strings.ToLower(req.PathValue("tunnel"))
	r.mu.Lock()
	t := r.tunnels[id]
	delete(r.tunnels, id)
	r.mu.Unlock()
	if t == nil {
		http.NotFound(w, req)
		return
	}
	defer close(t.done)

	desktop, err := websocket.Accept(w, req, accept)
	if err != nil {
		return
	}
	defer desktop.CloseNow()

	ctx, cancel := context.WithTimeout(req.Context(), lifetime)
	defer cancel()
	go func() {
		pipe(ctx, t.phone, desktop)
		cancel()
	}()
	pipe(ctx, desktop, t.phone)
}

func pipe(ctx context.Context, dst, src *websocket.Conn) {
	left := budget
	for {
		kind, data, err := src.Read(ctx)
		if err != nil {
			return
		}
		left -= len(data)
		if left < 0 || dst.Write(ctx, kind, data) != nil {
			return
		}
	}
}

// browsers derive the relay domain from the id in the phone's Bluetooth advert
func domain(id uint16) string {
	sum := sha256.Sum256(append([]byte("caBLEv2 tunnel server domain"), byte(id), byte(id>>8), 0))
	value := binary.LittleEndian.Uint64(sum[:8])
	tld := []string{"com", "org", "net", "info"}[value&3]
	name := ""
	for value >>= 2; value != 0; value >>= 5 {
		name += string("abcdefghijklmnopqrstuvwxyz234567"[value&31])
	}
	return "cable." + name + "." + tld
}

func main() {
	addr := flag.String("addr", ":8080", "listen address")
	id := flag.Uint("domain", 0, "print the domain of a relay id (256 to 65535) and exit")
	flag.Parse()
	if *id != 0 {
		fmt.Println(domain(uint16(*id)))
		return
	}

	r := &relay{tunnels: map[string]*tunnel{}}
	http.HandleFunc("GET /cable/new/{tunnel}", r.open)
	http.HandleFunc("GET /cable/connect/{routing}/{tunnel}", r.connect)
	log.Fatal(http.ListenAndServe(*addr, nil))
}
