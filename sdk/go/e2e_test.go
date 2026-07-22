//go:build e2e

// This file is compiled only under `-tags e2e`. It drives a REAL running zyncd with the
// real client, closing the SDK → server → op-log loop. Boot the server and run it with
// `sdk/smoke_e2e.sh`; a plain `go test ./...` skips it entirely (the tag excludes the file).
package zync

import (
	"context"
	"net"
	"os"
	"strings"
	"testing"
	"time"
)

func TestE2EAgainstRunningServer(t *testing.T) {
	addr := os.Getenv("ZYNC_E2E_ADDR")
	token := os.Getenv("ZYNC_BOT_TOKEN")
	if addr == "" || token == "" {
		t.Skip("set ZYNC_E2E_ADDR and ZYNC_BOT_TOKEN — see sdk/smoke_e2e.sh")
	}
	waitForServer(t, addr)

	c := New(addr, token)
	ctx := context.Background()

	created, err := c.Create(ctx, "e2e from Go", "inbox")
	if err != nil {
		t.Fatalf("create: %v", err)
	}
	if created.Status != "committed" || created.NodeID == "" {
		t.Fatalf("unexpected create result: %+v", created)
	}

	if cm, err := c.Comment(ctx, created.NodeID, "auto note from Go"); err != nil || cm.Status != "committed" {
		t.Fatalf("comment: result=%+v err=%v", cm, err)
	}

	pr, err := c.Propose(ctx, created.NodeID, "dueDate", 1893456000000)
	if err != nil {
		t.Fatalf("propose: %v", err)
	}
	if pr.Status != "proposed" {
		t.Fatalf("expected proposed, got %+v", pr)
	}

	// A wrong token is rejected as a typed 401.
	if _, err := New(addr, "definitely-wrong").Create(ctx, "nope", "inbox"); err == nil {
		t.Fatal("expected an error for a bad token")
	} else if ae, ok := err.(*APIError); !ok || ae.Status != 401 {
		t.Fatalf("expected APIError 401, got %v", err)
	}
}

// waitForServer retries a TCP dial so the test tolerates a server that is still coming up.
func waitForServer(t *testing.T, addr string) {
	host := strings.TrimPrefix(strings.TrimPrefix(addr, "http://"), "https://")
	deadline := time.Now().Add(30 * time.Second)
	for time.Now().Before(deadline) {
		if conn, err := net.DialTimeout("tcp", host, time.Second); err == nil {
			_ = conn.Close()
			return
		}
		time.Sleep(300 * time.Millisecond)
	}
	t.Fatalf("server at %s never became reachable", addr)
}
