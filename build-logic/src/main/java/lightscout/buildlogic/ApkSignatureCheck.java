package lightscout.buildlogic;

import com.android.apksig.ApkVerifier;
import com.android.apksig.apk.ApkFormatException;
import java.io.IOException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Read-only check that a release APK is signed with APK Signature Scheme v2 <b>and</b> v3
 * (CP-15 §8.6, SR-34, SR-35, CP-00A R-86). Uses {@code com.android.apksig.ApkVerifier} from the AGP
 * build classpath. It never signs and never touches key material.
 */
public final class ApkSignatureCheck {

    private ApkSignatureCheck() {
    }

    /**
     * Verifies the APK signatures for minSdk {@value LightScoutModulePlugin#MIN_SDK} and newer.
     *
     * @param apk the APK to check
     * @return SHA-256 fingerprints (lower-case hex) of the signer certificates
     * @throws IllegalArgumentException if the APK is not a verifiable v2 + v3 signed APK
     */
    public static List<String> requireV2AndV3(Path apk) {
        ApkVerifier.Result result;
        try {
            result = new ApkVerifier.Builder(apk.toFile())
                    .setMinCheckedPlatformVersion(LightScoutModulePlugin.MIN_SDK)
                    .build()
                    .verify();
        } catch (IOException | ApkFormatException | NoSuchAlgorithmException | IllegalStateException e) {
            throw new IllegalArgumentException("Cannot verify the APK signature of " + apk.getFileName()
                    + ": " + e.getMessage(), e);
        }
        if (!result.isVerified() || !result.isVerifiedUsingV2Scheme() || !result.isVerifiedUsingV3Scheme()) {
            throw new IllegalArgumentException("APK " + apk.getFileName() + " is not signed with v2 + v3 "
                    + "(verified=" + result.isVerified() + ", v2=" + result.isVerifiedUsingV2Scheme()
                    + ", v3=" + result.isVerifiedUsingV3Scheme() + "; errors: " + result.getAllErrors()
                    + "). The PO signs the release manually with apksigner (v2 + v3) before this step.");
        }
        List<String> fingerprints = new ArrayList<>();
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            for (X509Certificate certificate : result.getSignerCertificates()) {
                fingerprints.add(HexFormat.of().formatHex(sha256.digest(certificate.getEncoded())));
            }
        } catch (NoSuchAlgorithmException | CertificateEncodingException e) {
            throw new IllegalStateException("Cannot compute the signer certificate fingerprint", e);
        }
        return fingerprints;
    }
}
