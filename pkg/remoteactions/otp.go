package remoteactions

import (
	"fmt"
	"strings"
	"time"

	"github.com/pquerna/otp"
	"github.com/pquerna/otp/totp"
)

// Generate computes the current TOTP code for cred at the given moment
// and returns (code, nextStepBoundary). The next-step time is the
// inclusive upper edge of the current TOTP window — used by the UI
// countdown so the picker shows "next in Ns" without a separate query.
//
// Defaults for zero-valued fields:
//
//	Algorithm "" -> SHA1
//	Digits   0  -> 6
//	Period   0  -> 30
//
// SecretB32 may be lowercase or include whitespace; the function
// normalizes via NormalizeBase32Secret. An invalid secret returns an
// error rather than silently substituting.
func Generate(cred *RemoteOTPCredential, now time.Time) (string, time.Time, error) {
	if cred == nil {
		return "", time.Time{}, fmt.Errorf("remoteactions: nil credential")
	}
	secret, err := NormalizeBase32Secret(cred.SecretB32)
	if err != nil {
		return "", time.Time{}, fmt.Errorf("secret: %w", err)
	}
	algo := otpAlgorithm(cred.Algorithm)
	digits := otpDigits(cred.Digits)
	period := uint(cred.Period)
	if period == 0 {
		period = 30
	}
	code, err := totp.GenerateCodeCustom(secret, now.UTC(), totp.ValidateOpts{
		Period:    period,
		Digits:    digits,
		Algorithm: algo,
	})
	if err != nil {
		return "", time.Time{}, fmt.Errorf("totp: %w", err)
	}
	stepSec := int64(period)
	curStep := now.Unix() / stepSec
	next := time.Unix((curStep+1)*stepSec, 0).UTC()
	return code, next, nil
}

func otpAlgorithm(s string) otp.Algorithm {
	switch strings.ToUpper(s) {
	case "", "SHA1":
		return otp.AlgorithmSHA1
	case "SHA256":
		return otp.AlgorithmSHA256
	case "SHA512":
		return otp.AlgorithmSHA512
	default:
		return otp.AlgorithmSHA1
	}
}

func otpDigits(n int) otp.Digits {
	switch n {
	case 0, 6:
		return otp.DigitsSix
	case 8:
		return otp.DigitsEight
	default:
		return otp.DigitsSix
	}
}
