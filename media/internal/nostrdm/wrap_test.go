package nostrdm

import (
	"context"
	"testing"
	"time"

	"github.com/nbd-wtf/go-nostr"
	"github.com/nbd-wtf/go-nostr/keyer"
	"github.com/nbd-wtf/go-nostr/nip19"
	"github.com/nbd-wtf/go-nostr/nip59"
)

type party struct {
	sec string
	pub string
	kr  nostr.Keyer
}

func newParty(t *testing.T) party {
	t.Helper()
	sec := nostr.GeneratePrivateKey()
	pub, err := nostr.GetPublicKey(sec)
	if err != nil {
		t.Fatal(err)
	}
	kr, err := keyer.NewPlainKeySigner(sec)
	if err != nil {
		t.Fatal(err)
	}
	return party{sec, pub, kr}
}

func TestWrapRoundTrip(t *testing.T) {
	ctx := context.Background()
	server, operator := newParty(t), newParty(t)

	wrap, err := wrapFor(ctx, server.kr, operator.pub, "hallo")
	if err != nil {
		t.Fatal(err)
	}
	if wrap.Kind != nostr.KindGiftWrap {
		t.Fatalf("wrap kind = %d", wrap.Kind)
	}
	if tag := wrap.Tags.GetFirst([]string{"p"}); tag == nil || (*tag)[1] != operator.pub {
		t.Fatalf("wrap p tag = %v, want the operator", wrap.Tags)
	}
	if wrap.PubKey == server.pub || wrap.PubKey == operator.pub {
		t.Fatal("wrap must be signed by an ephemeral key")
	}

	in, err := unwrap(ctx, operator.kr, wrap)
	if err != nil {
		t.Fatal(err)
	}
	if in.Content != "hallo" || in.Kind != 14 || in.Sender != server.pub {
		t.Fatalf("got %+v", in)
	}
	if in.WrapAt != int64(wrap.CreatedAt) {
		t.Fatalf("wrap identity not carried over: %+v", in)
	}
}

func TestOperatorReplyRoundTrip(t *testing.T) {
	ctx := context.Background()
	server, operator := newParty(t), newParty(t)

	wrap, err := wrapFor(ctx, operator.kr, server.pub, "weg")
	if err != nil {
		t.Fatal(err)
	}
	in, err := unwrap(ctx, server.kr, wrap)
	if err != nil {
		t.Fatal(err)
	}
	if in.Sender != operator.pub || in.Content != "weg" {
		t.Fatalf("got %+v", in)
	}
}

func TestLookback(t *testing.T) {
	const day, now = int64(86400), int64(100 * 86400)
	for name, c := range map[string]struct{ last, want int64 }{
		"none":      {0, now - 9*day},
		"recent":    {now - 3600, now - 3600 - 2*day},
		"too old":   {now - 30*day, now - 9*day},
		"7d border": {now - 7*day, now - 9*day},
	} {
		if got := lookback(c.last, now); got != c.want {
			t.Errorf("%s: got %d want %d", name, got, c.want)
		}
	}
}

func TestUnwrapSenderIsSealSigner(t *testing.T) {
	ctx := context.Background()
	server, operator, stranger := newParty(t), newParty(t), newParty(t)

	// The rumor claims to come from the operator, but the seal is the
	// stranger's; the seal is what counts.
	rumor := nostr.Event{
		Kind:      nostr.KindDirectMessage,
		Content:   "weg",
		Tags:      nostr.Tags{{"p", server.pub}},
		CreatedAt: nostr.Now(),
		PubKey:    operator.pub,
	}
	rumor.ID = rumor.GetID()
	wrap, err := nip59.GiftWrap(rumor, server.pub,
		func(s string) (string, error) { return stranger.kr.Encrypt(ctx, s, server.pub) },
		func(e *nostr.Event) error { return stranger.kr.SignEvent(ctx, e) },
		nil)
	if err != nil {
		t.Fatal(err)
	}
	in, err := unwrap(ctx, server.kr, wrap)
	if err != nil {
		t.Fatal(err)
	}
	if in.Sender != stranger.pub {
		t.Fatalf("Sender = %s, want the stranger %s", in.Sender, stranger.pub)
	}
}

func TestUnwrapGarbage(t *testing.T) {
	server := newParty(t)
	junk := nostr.Event{Kind: nostr.KindGiftWrap, Content: "not encrypted at all", PubKey: newParty(t).pub, CreatedAt: nostr.Now()}
	if _, err := unwrap(context.Background(), server.kr, junk); err == nil {
		t.Fatal("expected an error")
	}
}

func TestNewRejectsBadKeys(t *testing.T) {
	ctx := context.Background()
	good, operator := newParty(t), newParty(t)
	relays := []string{"wss://own.example"}
	if _, err := New(ctx, "nsec1nonsense", operator.pub, relays, "https://m", nil); err == nil {
		t.Error("bad nsec accepted")
	}
	if _, err := New(ctx, good.sec, "nothex", relays, "https://m", nil); err == nil {
		t.Error("bad operator accepted")
	}
	if _, err := New(ctx, good.sec, operator.pub, nil, "https://m", nil); err == nil {
		t.Error("empty own relays accepted")
	}
	if _, err := New(ctx, good.sec, good.pub, relays, "https://m", nil); err == nil {
		t.Error("the operator's own key as server key was accepted")
	}
	c, err := New(ctx, good.sec, operator.pub, relays, "https://m", nil)
	if err != nil {
		t.Fatal(err)
	}
	if c.PublicKey() != good.pub {
		t.Error("PublicKey mismatch")
	}
	nsec, err := nip19.EncodePrivateKey(good.sec)
	if err != nil {
		t.Fatal(err)
	}
	c, err = New(ctx, nsec, operator.pub, relays, "https://m", nil)
	if err != nil {
		t.Fatalf("nsec1 rejected: %v", err)
	}
	if c.PublicKey() != good.pub {
		t.Error("nsec PublicKey mismatch")
	}
}

func TestProfileContentHasPublicURL(t *testing.T) {
	good, operator := newParty(t), newParty(t)
	c, err := New(context.Background(), good.sec, operator.pub, []string{"wss://own.example"}, "https://media.example", nil)
	if err != nil {
		t.Fatal(err)
	}
	b, err := c.profileContent()
	if err != nil {
		t.Fatal(err)
	}
	if want := `{"about":"Meldungen von https://media.example","name":"monostr media"}`; string(b) != want {
		t.Fatalf("profile = %s", b)
	}
}

func TestUnwrapIDIsRumorID(t *testing.T) {
	ctx := context.Background()
	server, operator := newParty(t), newParty(t)
	wrap, err := wrapFor(ctx, operator.kr, server.pub, "weg")
	if err != nil {
		t.Fatal(err)
	}
	honest, err := unwrap(ctx, server.kr, wrap)
	if err != nil {
		t.Fatal(err)
	}
	wrap.ID = "ff" + wrap.ID[2:]
	in, err := unwrap(ctx, server.kr, wrap)
	if err != nil {
		t.Fatal(err)
	}
	if in.WrapID != honest.WrapID || in.WrapID == wrap.ID {
		t.Fatalf("WrapID follows the wrap id: %q", in.WrapID)
	}
	if d := time.Now().Unix() - in.SentAt; in.SentAt != honest.SentAt || d < 0 || d > 5 {
		t.Fatalf("SentAt = %d", in.SentAt)
	}
}
