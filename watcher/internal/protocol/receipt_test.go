package protocol

import (
	"strings"
	"testing"

	"github.com/nbd-wtf/go-nostr"
)

func TestBuildReceiptTagsAndVector(t *testing.T) {
	in := Intent{
		ID:        strings.Repeat("d", 64),
		Sender:    strings.Repeat("e", 64),
		Recipient: strings.Repeat("c", 64),
		NoteID:    strings.Repeat("b", 64),
		Type:      "boost",
	}
	ev := BuildReceipt(in, 4949570000, 1700000000)
	ev.PubKey = strings.Repeat("a", 64)
	if ev.Kind != KindTipReceipt || ev.Content != "" {
		t.Fatalf("kind/content: %d %q", ev.Kind, ev.Content)
	}
	want := nostr.Tags{
		{"e", in.NoteID}, {"p", in.Recipient}, {"P", in.Sender},
		{"amount", "4949570000"}, {"intent", in.ID}, {"type", "boost"},
	}
	if len(ev.Tags) != len(want) {
		t.Fatalf("tags: %v", ev.Tags)
	}
	for i := range want {
		if ev.Tags[i][0] != want[i][0] || ev.Tags[i][1] != want[i][1] {
			t.Fatalf("tag %d: %v", i, ev.Tags[i])
		}
	}
	// reference id computed with python hashlib/json over the NIP-01 array
	// [0,"a"*64,1700000000,9739,<tags>,""]
	const wantID = "6301477f9d2268babf0020cc7c327d348ffa240e6ff15c2efc8a94edef10a1b7"
	if got := ev.GetID(); got != wantID {
		t.Fatalf("id %s", got)
	}
}

func TestBuildReceiptTipType(t *testing.T) {
	in := Intent{
		ID:        strings.Repeat("d", 64),
		Sender:    strings.Repeat("e", 64),
		Recipient: strings.Repeat("c", 64),
		NoteID:    strings.Repeat("b", 64),
		Type:      "tip",
	}
	ev := BuildReceipt(in, 4949570000, 1700000000)
	if tag := ev.Tags.Find("type"); tag == nil || tag[1] != "tip" {
		t.Fatalf("type tag: %v", ev.Tags)
	}
}

func TestBuildReceiptOmitsNoteAndSender(t *testing.T) {
	base := Intent{
		ID: strings.Repeat("d", 64), Sender: strings.Repeat("e", 64),
		Recipient: strings.Repeat("c", 64), NoteID: strings.Repeat("b", 64), Type: "tip",
	}
	cases := []struct {
		name   string
		mutate func(*Intent)
		want   []string
	}{
		{"note, public", func(*Intent) {}, []string{"e", "p", "P", "amount", "intent", "type"}},
		{"profile, public", func(in *Intent) { in.NoteID = "" }, []string{"p", "P", "amount", "intent", "type"}},
		{"note, anonymous", func(in *Intent) { in.Anon = true }, []string{"e", "p", "amount", "intent", "type"}},
		{"profile, anonymous", func(in *Intent) { in.NoteID = ""; in.Anon = true }, []string{"p", "amount", "intent", "type"}},
	}
	for _, c := range cases {
		in := base
		c.mutate(&in)
		ev := BuildReceipt(in, 5, 1700000000)
		var names []string
		for _, tag := range ev.Tags {
			names = append(names, tag[0])
		}
		if strings.Join(names, ",") != strings.Join(c.want, ",") {
			t.Errorf("%s: tags %v", c.name, ev.Tags)
		}
		if p := ev.Tags.Find("p"); p == nil || p[1] != in.Recipient {
			t.Errorf("%s: p tag %v", c.name, ev.Tags)
		}
	}
}

func TestBuildDeletion(t *testing.T) {
	ev := BuildDeletion(strings.Repeat("f", 64), 1700000000)
	if ev.Kind != KindDeletion {
		t.Fatalf("kind %d", ev.Kind)
	}
	if e := ev.Tags.Find("e"); e == nil || e[1] != strings.Repeat("f", 64) {
		t.Fatalf("e tag: %v", ev.Tags)
	}
	if k := ev.Tags.Find("k"); k == nil || k[1] != "9739" {
		t.Fatalf("k tag: %v", ev.Tags)
	}
	if ev.Content == "" {
		t.Fatal("deletion should carry a human-readable reason")
	}
}

func TestSignedReceiptVerifies(t *testing.T) {
	sk := nostr.GeneratePrivateKey()
	ev := BuildReceipt(Intent{ID: strings.Repeat("d", 64), Sender: strings.Repeat("e", 64),
		Recipient: strings.Repeat("c", 64), NoteID: strings.Repeat("b", 64), Type: "like"}, 1, 1700000000)
	if err := ev.Sign(sk); err != nil {
		t.Fatal(err)
	}
	if ok, err := ev.CheckSignature(); !ok || err != nil {
		t.Fatalf("signature: %v %v", ok, err)
	}
}
