package lws

import (
	"errors"
	"testing"
)

const bareHook = `{
  "event": "tx-confirmation",
  "payment_id": "df034c176eca3296",
  "token": "1234",
  "confirmations": 1,
  "id": "fa10a4db485145f1a24dc09c19a79d43",
  "tx_info": {
    "id": {"high": 0, "low": 5550229},
    "block": 2192100,
    "index": 0,
    "amount": 4949570000,
    "timestamp": 1678324181,
    "tx_hash": "901f9a2a919b6312131537ff6117d56ce2c0dc1f1341b845d7667299e1ef892f",
    "tx_prefix_hash": "89685cb7acb836fde30fae8be5d8b884e92706df086960d0508e146979ef80dc",
    "tx_public": "54c153792e47c1da8ceb3979560c424c1928b7b4a089c1c8b3ce99c563e1d240",
    "rct_mask": "f3449407dc3721299b5309c0c336a17daeebce55165ddd447ba28bbd1f46c201",
    "payment_id": "df034c176eca3296",
    "unlock_time": 0,
    "mixin_count": 15,
    "coinbase": false
  }
}`

func TestParseHookBare(t *testing.T) {
	h, err := ParseHook([]byte(bareHook))
	if err != nil {
		t.Fatal(err)
	}
	if h.PaymentID != "df034c176eca3296" || h.Token != "1234" || h.Confirmations != 1 ||
		h.EventID != "fa10a4db485145f1a24dc09c19a79d43" || h.Amount != 4949570000 || h.Block != 2192100 ||
		h.TxHash != "901f9a2a919b6312131537ff6117d56ce2c0dc1f1341b845d7667299e1ef892f" {
		t.Fatalf("hook %+v", h)
	}
}

func TestParseHookWrappedAndEventIDField(t *testing.T) {
	wrapped := `{"index": 2, "event": ` + bareHook + `}`
	h, err := ParseHook([]byte(wrapped))
	if err != nil || h.TxHash == "" {
		t.Fatalf("wrapped: %+v %v", h, err)
	}
	alt := `{"event":"tx-confirmation","payment_id":"df034c176eca3296","token":"t","confirmations":0,
	  "event_id":"3894f98f5dd54af5857e4f8a961a4e57","tx_info":{"amount":1,"tx_hash":"` + h.TxHash + `"}}`
	h, err = ParseHook([]byte(alt))
	if err != nil || h.EventID != "3894f98f5dd54af5857e4f8a961a4e57" || h.Confirmations != 0 || h.Block != 0 {
		t.Fatalf("event_id form: %+v %v", h, err)
	}
}

func TestParseHookRejects(t *testing.T) {
	cases := map[string]string{
		"not json":     `nope`,
		"other event":  `{"event":"tx-spend","payment_id":"a","token":"t","id":"x","tx_info":{"amount":1,"tx_hash":"h"}}`,
		"no tx_info":   `{"event":"tx-confirmation","payment_id":"a","token":"t","id":"x"}`,
		"no tx_hash":   `{"event":"tx-confirmation","payment_id":"a","token":"t","id":"x","tx_info":{"amount":1}}`,
		"amount float": `{"event":"tx-confirmation","payment_id":"a","token":"t","id":"x","tx_info":{"amount":1.5,"tx_hash":"h"}}`,
		"amount huge":  `{"event":"tx-confirmation","payment_id":"a","token":"t","id":"x","tx_info":{"amount":99999999999999999999,"tx_hash":"h"}}`,
		"amount zero":  `{"event":"tx-confirmation","payment_id":"a","token":"t","id":"x","tx_info":{"amount":0,"tx_hash":"h"}}`,
		"no token":     `{"event":"tx-confirmation","payment_id":"a","id":"x","tx_info":{"amount":1,"tx_hash":"h"}}`,
		"no id":        `{"event":"tx-confirmation","payment_id":"a","token":"t","tx_info":{"amount":1,"tx_hash":"h"}}`,
		"empty":        ``,
	}
	for name, body := range cases {
		if _, err := ParseHook([]byte(body)); !errors.Is(err, ErrBadHook) {
			t.Errorf("%s: want ErrBadHook, got %v", name, err)
		}
	}
}
