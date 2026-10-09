// Package lws talks to the monero-lws admin REST API and parses its webhook
// callbacks.
package lws

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"math"
)

var ErrBadHook = errors.New("bad lws hook payload")

// Hook is a tx-confirmation callback, normalised across the two payload
// shapes monero-lws has used (bare object vs {"index","event"}; "id" vs
// "event_id").
type Hook struct {
	PaymentID     string
	Token         string
	EventID       string
	TxHash        string
	Confirmations uint32
	Amount        int64
	Block         uint64
}

type rawTxInfo struct {
	Amount json.Number `json:"amount"`
	TxHash string      `json:"tx_hash"`
	Block  json.Number `json:"block"`
}

type rawHook struct {
	Event         string     `json:"event"`
	PaymentID     string     `json:"payment_id"`
	Token         *string    `json:"token"`
	Confirmations uint32     `json:"confirmations"`
	ID            string     `json:"id"`
	EventID       string     `json:"event_id"`
	TxInfo        *rawTxInfo `json:"tx_info"`
}

func ParseHook(body []byte) (Hook, error) {
	var probe struct {
		Event json.RawMessage `json:"event"`
	}
	dec := json.NewDecoder(bytes.NewReader(body))
	dec.UseNumber()
	if err := dec.Decode(&probe); err != nil {
		return Hook{}, fmt.Errorf("%w: %v", ErrBadHook, err)
	}
	payload := body
	if len(probe.Event) > 0 && probe.Event[0] == '{' {
		payload = probe.Event // wrapped form
	}
	var r rawHook
	dec = json.NewDecoder(bytes.NewReader(payload))
	dec.UseNumber()
	if err := dec.Decode(&r); err != nil {
		return Hook{}, fmt.Errorf("%w: %v", ErrBadHook, err)
	}
	if r.Event != "tx-confirmation" {
		return Hook{}, fmt.Errorf("%w: event %q", ErrBadHook, r.Event)
	}
	if r.Token == nil || r.TxInfo == nil || r.TxInfo.TxHash == "" {
		return Hook{}, fmt.Errorf("%w: missing fields", ErrBadHook)
	}
	id := r.ID
	if id == "" {
		id = r.EventID
	}
	if id == "" {
		return Hook{}, fmt.Errorf("%w: missing event id", ErrBadHook)
	}
	amount, err := r.TxInfo.Amount.Int64()
	if err != nil || amount <= 0 || amount == math.MaxInt64 {
		return Hook{}, fmt.Errorf("%w: bad amount", ErrBadHook)
	}
	var block uint64
	if r.TxInfo.Block != "" {
		b, err := r.TxInfo.Block.Int64()
		if err != nil || b < 0 {
			return Hook{}, fmt.Errorf("%w: bad block", ErrBadHook)
		}
		block = uint64(b)
	}
	return Hook{
		PaymentID: r.PaymentID, Token: *r.Token, EventID: id, TxHash: r.TxInfo.TxHash,
		Confirmations: r.Confirmations, Amount: amount, Block: block,
	}, nil
}
