// Package zync is a tiny, dependency-free client for the Zync external op API
// (see INTEGRATE.md). It uses only the Go standard library: net/http +
// encoding/json, with crypto/rand for idempotency keys.
package zync

import (
	"bytes"
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
)

// Intent is one high-level operation. Op selects the verb; the rest are its arguments.
type Intent struct {
	Op      string         `json:"op"`
	Kind    string         `json:"kind,omitempty"`
	Title   string         `json:"title,omitempty"`
	Parent  string         `json:"parent,omitempty"`
	Target  string         `json:"target,omitempty"`
	Field   string         `json:"field,omitempty"`
	Value   any            `json:"value,omitempty"`
	Text    string         `json:"text,omitempty"`
	Context string         `json:"context,omitempty"`
	Tags    []string       `json:"tags,omitempty"`
	Fields  map[string]any `json:"fields,omitempty"`
	BlobRef string         `json:"blobRef,omitempty"`
	Type    string         `json:"type,omitempty"`
	Name    string         `json:"name,omitempty"`
}

// Envelope is a batch of intents applied atomically.
type Envelope struct {
	IdempotencyKey string   `json:"idempotencyKey,omitempty"`
	Mode           string   `json:"mode,omitempty"` // "commit" (default) or "propose"
	Intents        []Intent `json:"intents"`
}

// IntentResult is the per-intent outcome.
type IntentResult struct {
	Op     string `json:"op"`
	NodeID string `json:"nodeId,omitempty"`
	Status string `json:"status"` // committed | proposed | error
	Error  string `json:"error,omitempty"`
}

// Result is the envelope response.
type Result struct {
	Results []IntentResult `json:"results"`
}

// APIError is a non-2xx response from the op API.
type APIError struct {
	Status int
	Body   string
}

func (e *APIError) Error() string { return fmt.Sprintf("zync API HTTP %d: %s", e.Status, e.Body) }

// Client talks to a Zync server's op API.
type Client struct {
	BaseURL string
	Token   string
	HTTP    *http.Client
}

// New returns a client with a default http.Client.
func New(baseURL, token string) *Client {
	return &Client{BaseURL: baseURL, Token: token, HTTP: http.DefaultClient}
}

// Submit sends an envelope. An idempotency key is generated if absent, so retries are safe.
func (c *Client) Submit(ctx context.Context, env Envelope) (*Result, error) {
	if env.IdempotencyKey == "" {
		env.IdempotencyKey = randomKey()
	}
	body, err := json.Marshal(env)
	if err != nil {
		return nil, err
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, c.BaseURL+"/api/ops", bytes.NewReader(body))
	if err != nil {
		return nil, err
	}
	req.Header.Set("Authorization", "Bearer "+c.Token)
	req.Header.Set("Content-Type", "application/json")
	resp, err := c.HTTP.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	raw, _ := io.ReadAll(resp.Body)
	if resp.StatusCode >= 300 {
		return nil, &APIError{Status: resp.StatusCode, Body: string(raw)}
	}
	var out Result
	if err := json.Unmarshal(raw, &out); err != nil {
		return nil, err
	}
	return &out, nil
}

// Create is a convenience for a single create intent.
func (c *Client) Create(ctx context.Context, title, parent string) (*IntentResult, error) {
	return c.one(ctx, Envelope{Intents: []Intent{{Op: "create", Title: title, Parent: parent}}})
}

// Comment adds a comment to a node.
func (c *Client) Comment(ctx context.Context, target, text string) (*IntentResult, error) {
	return c.one(ctx, Envelope{Intents: []Intent{{Op: "comment", Target: target, Text: text}}})
}

// SetField sets a field on a node.
func (c *Client) SetField(ctx context.Context, target, field string, value any) (*IntentResult, error) {
	return c.one(ctx, Envelope{Intents: []Intent{{Op: "setField", Target: target, Field: field, Value: value}}})
}

// Propose proposes a field edit for a human to accept (does not mutate live state).
func (c *Client) Propose(ctx context.Context, target, field string, value any) (*IntentResult, error) {
	return c.one(ctx, Envelope{Mode: "propose", Intents: []Intent{{Op: "setField", Target: target, Field: field, Value: value}}})
}

// UploadBlob content-addresses a blob; returns its key for use in an attach intent.
func (c *Client) UploadBlob(ctx context.Context, data []byte) (string, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodPut, c.BaseURL+"/api/blobs", bytes.NewReader(data))
	if err != nil {
		return "", err
	}
	req.Header.Set("Authorization", "Bearer "+c.Token)
	resp, err := c.HTTP.Do(req)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()
	raw, _ := io.ReadAll(resp.Body)
	if resp.StatusCode >= 300 {
		return "", &APIError{Status: resp.StatusCode, Body: string(raw)}
	}
	var out struct {
		Key string `json:"key"`
	}
	if err := json.Unmarshal(raw, &out); err != nil {
		return "", err
	}
	return out.Key, nil
}

func (c *Client) one(ctx context.Context, env Envelope) (*IntentResult, error) {
	res, err := c.Submit(ctx, env)
	if err != nil {
		return nil, err
	}
	if len(res.Results) == 0 {
		return nil, fmt.Errorf("zync: empty result")
	}
	return &res.Results[0], nil
}

func randomKey() string {
	b := make([]byte, 16)
	_, _ = rand.Read(b)
	return hex.EncodeToString(b)
}
