package main

import (
	"context"
	"flag"
	"log"
	"net/http"
	"strings"
	"sync"
	"time"

	"github.com/coder/websocket"
)

const lifetime = 5 * time.Minute

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
	for {
		kind, data, err := src.Read(ctx)
		if err != nil {
			return
		}
		if dst.Write(ctx, kind, data) != nil {
			return
		}
	}
}

func main() {
	addr := flag.String("addr", ":8080", "listen address")
	flag.Parse()

	r := &relay{tunnels: map[string]*tunnel{}}
	http.HandleFunc("GET /cable/new/{tunnel}", r.open)
	http.HandleFunc("GET /cable/connect/{routing}/{tunnel}", r.connect)
	log.Fatal(http.ListenAndServe(*addr, nil))
}
