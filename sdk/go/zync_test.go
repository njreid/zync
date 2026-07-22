package zync

import (
	"context"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestCreateSendsAuthedEnvelope(t *testing.T) {
	var gotAuth, gotBody string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotAuth = r.Header.Get("Authorization")
		b, _ := io.ReadAll(r.Body)
		gotBody = string(b)
		_, _ = w.Write([]byte(`{"results":[{"op":"create","nodeId":"01JZ","status":"committed"}]}`))
	}))
	defer srv.Close()

	c := New(srv.URL, "secret")
	res, err := c.Create(context.Background(), "Hello from a bot", "inbox")
	if err != nil {
		t.Fatalf("Create: %v", err)
	}
	if res.Status != "committed" || res.NodeID != "01JZ" {
		t.Fatalf("unexpected result: %+v", res)
	}
	if gotAuth != "Bearer secret" {
		t.Fatalf("auth header: %q", gotAuth)
	}
	if !strings.Contains(gotBody, `"op":"create"`) || !strings.Contains(gotBody, "Hello from a bot") {
		t.Fatalf("body: %s", gotBody)
	}
	if !strings.Contains(gotBody, "idempotencyKey") {
		t.Fatalf("expected an auto idempotency key: %s", gotBody)
	}
}

func TestProposeSetsMode(t *testing.T) {
	var gotBody string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		b, _ := io.ReadAll(r.Body)
		gotBody = string(b)
		_, _ = w.Write([]byte(`{"results":[{"op":"setField","nodeId":"01S","status":"proposed"}]}`))
	}))
	defer srv.Close()

	res, err := New(srv.URL, "secret").Propose(context.Background(), "01T", "dueDate", 123)
	if err != nil {
		t.Fatal(err)
	}
	if res.Status != "proposed" {
		t.Fatalf("status: %s", res.Status)
	}
	if !strings.Contains(gotBody, `"mode":"propose"`) {
		t.Fatalf("mode missing: %s", gotBody)
	}
}

func TestUploadBlobReturnsKey(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte(`{"key":"blob-abc"}`))
	}))
	defer srv.Close()

	key, err := New(srv.URL, "secret").UploadBlob(context.Background(), []byte("bytes"))
	if err != nil {
		t.Fatal(err)
	}
	if key != "blob-abc" {
		t.Fatalf("key: %s", key)
	}
}

func TestNonSuccessIsAnError(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		http.Error(w, "intents must be 1..200", http.StatusBadRequest)
	}))
	defer srv.Close()

	_, err := New(srv.URL, "secret").Create(context.Background(), "x", "inbox")
	apiErr, ok := err.(*APIError)
	if !ok || apiErr.Status != 400 {
		t.Fatalf("expected APIError 400, got %v", err)
	}
}
