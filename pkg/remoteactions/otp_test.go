package remoteactions

import (
	"testing"
	"time"
)

// RFC 6238 Appendix B reference values for the SHA1 secret
// "12345678901234567890" (base32: GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ).
func TestGenerateSHA1Vectors(t *testing.T) {
	cred := &RemoteOTPCredential{
		SecretB32: "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ",
		Algorithm: "sha1",
		Digits:    8,
		Period:    30,
	}
	cases := []struct {
		t    int64
		want string
	}{
		{59, "94287082"},
		{1111111109, "07081804"},
		{1111111111, "14050471"},
		{1234567890, "89005924"},
		{2000000000, "69279037"},
	}
	for _, tc := range cases {
		got, _, err := Generate(cred, time.Unix(tc.t, 0))
		if err != nil {
			t.Fatalf("generate t=%d: %v", tc.t, err)
		}
		if got != tc.want {
			t.Fatalf("t=%d got %s want %s", tc.t, got, tc.want)
		}
	}
}

func TestGenerateNextStep(t *testing.T) {
	cred := &RemoteOTPCredential{
		SecretB32: "JBSWY3DPEHPK3PXP",
		Algorithm: "sha1",
		Digits:    6,
		Period:    30,
	}
	// At t=10, next step boundary is t=30.
	_, next, err := Generate(cred, time.Unix(10, 0))
	if err != nil {
		t.Fatalf("err: %v", err)
	}
	if next.Unix() != 30 {
		t.Fatalf("next = %d, want 30", next.Unix())
	}
	// At t=30 (boundary), next step is t=60.
	_, next, _ = Generate(cred, time.Unix(30, 0))
	if next.Unix() != 60 {
		t.Fatalf("next = %d, want 60", next.Unix())
	}
}

func TestGenerateDefaultsForZeroFields(t *testing.T) {
	// A row inserted via Create with zero Algorithm/Digits/Period
	// should still produce a six-digit SHA1/30s code.
	cred := &RemoteOTPCredential{SecretB32: "JBSWY3DPEHPK3PXP"}
	got, _, err := Generate(cred, time.Unix(0, 0))
	if err != nil {
		t.Fatalf("err: %v", err)
	}
	if len(got) != 6 {
		t.Fatalf("got %s, want 6 digits", got)
	}
}
