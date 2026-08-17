package org.einkwiki.app.update;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public final class UpdatePolicyTest {
    private static final String APK_NAME = "EInkWiki-v1.2.3.apk";
    private static final String UPPERCASE_SHA256 = repeat("AB", 32);
    private static final String LOWERCASE_SHA256 = UPPERCASE_SHA256.toLowerCase();

    @Test
    public void semanticVersionsAreNormalizedAsNumericComponents() {
        assertEquals("1.2.3", UpdatePolicy.normalizedVersion(" v01.002.0003 "));
        assertEquals("1.2.3", UpdatePolicy.normalizedVersion("V1.2.3"));
        assertNull(UpdatePolicy.normalizedVersion("v1.2"));
        assertNull(UpdatePolicy.normalizedVersion("v1.2.3-beta"));
        assertNull(UpdatePolicy.normalizedVersion("v9223372036854775808.0.0"));
    }

    @Test
    public void newerVersionsAreComparedNumerically() {
        assertTrue(UpdatePolicy.isNewer("v1.10.0", "v1.9.9"));
        assertTrue(UpdatePolicy.isNewer("2.0.0", "v1.999.999"));
        assertFalse(UpdatePolicy.isNewer("v1.2.3", "1.2.3"));
        assertFalse(UpdatePolicy.isNewer("v1.2.2", "v1.2.3"));
        assertFalse(UpdatePolicy.isNewer("latest", "v1.2.3"));
    }

    @Test
    public void releaseAssetNamesAreDerivedFromAValidTag() {
        assertEquals(APK_NAME, UpdatePolicy.expectedApkName("v1.2.3"));
        assertEquals(APK_NAME + ".sha256", UpdatePolicy.expectedChecksumName("v1.2.3"));
        assertEquals(
                "https://github.com/TaoZang/EInkWiki/releases/download/v1.2.3/" + APK_NAME,
                UpdatePolicy.assetUrl("v1.2.3", APK_NAME)
        );
        assertNull(UpdatePolicy.expectedApkName("v1.2.3-beta"));
        assertNull(UpdatePolicy.expectedChecksumName("v1.2"));
        assertNull(UpdatePolicy.assetUrl("v1.2", APK_NAME));
        assertNull(UpdatePolicy.assetUrl("v1.2.3", "directory/" + APK_NAME));
    }

    @Test
    public void latestReleasePageUrlYieldsOnlyAnExactGitHubSemanticTag() {
        assertEquals(
                "v1.2.3",
                UpdatePolicy.releaseTagFromPageUrl(
                        "https://github.com/TaoZang/EInkWiki/releases/tag/v1.2.3")
        );
        List<String> rejectedUrls = Arrays.asList(
                "http://github.com/TaoZang/EInkWiki/releases/tag/v1.2.3",
                "https://github.com/SomeoneElse/EInkWiki/releases/tag/v1.2.3",
                "https://github.com/TaoZang/EInkWiki/releases/tag/v1.2.3?download=1",
                "https://github.com/TaoZang/EInkWiki/releases/tag/v1.2.3/extra",
                "https://github.com/TaoZang/EInkWiki/releases/tag/latest",
                "https://user@github.com/TaoZang/EInkWiki/releases/tag/v1.2.3"
        );
        for (String url : rejectedUrls) {
            assertNull(url, UpdatePolicy.releaseTagFromPageUrl(url));
        }
    }

    @Test
    public void onlyExactGitHubReleaseAssetUrlsAreAllowed() {
        String apkUrl = "https://github.com/TaoZang/EInkWiki/releases/download/v1.2.3/"
                + APK_NAME;
        String checksumName = APK_NAME + ".sha256";
        String checksumUrl = "https://github.com/TaoZang/EInkWiki/releases/download/v1.2.3/"
                + checksumName;
        assertTrue(UpdatePolicy.isAllowedAssetUrl(apkUrl, "v1.2.3", APK_NAME));
        assertTrue(UpdatePolicy.isAllowedAssetUrl(checksumUrl, "v1.2.3", checksumName));
    }

    @Test
    public void assetUrlAllowlistRejectsTransportHostPathAndSuffixChanges() {
        List<String> rejectedUrls = Arrays.asList(
                "http://github.com/TaoZang/EInkWiki/releases/download/v1.2.3/" + APK_NAME,
                "https://github.com.evil.example/TaoZang/EInkWiki/releases/download/v1.2.3/"
                        + APK_NAME,
                "https://api.github.com/TaoZang/EInkWiki/releases/download/v1.2.3/" + APK_NAME,
                "https://github.com/SomeoneElse/EInkWiki/releases/download/v1.2.3/" + APK_NAME,
                "https://github.com/TaoZang/EInkWiki/releases/download/v1.2.4/" + APK_NAME,
                "https://github.com/TaoZang/EInkWiki/releases/download/v1.2.3/other.apk",
                "https://github.com/TaoZang/EInkWiki/releases/download/v1.2.3/"
                        + APK_NAME + "?raw=1",
                "https://github.com/TaoZang/EInkWiki/releases/download/v1.2.3/"
                        + APK_NAME + "#download",
                "https://user@github.com/TaoZang/EInkWiki/releases/download/v1.2.3/" + APK_NAME,
                "https://github.com:443/TaoZang/EInkWiki/releases/download/v1.2.3/" + APK_NAME,
                "https://github.com/TaoZang/EInkWiki/releases/download/v1.2.3/"
                        + "%45InkWiki-v1.2.3.apk"
        );
        for (String url : rejectedUrls) {
            assertFalse(url, UpdatePolicy.isAllowedAssetUrl(url, "v1.2.3", APK_NAME));
        }
    }

    @Test
    public void assetRedirectsOnlyAllowTheGitHubReleaseCdn() {
        assertTrue(UpdatePolicy.isAllowedAssetRedirectUrl(
                "https://release-assets.githubusercontent.com/github-production-release-asset/"
                        + "id?sig=value"
        ));
        List<String> rejectedUrls = Arrays.asList(
                "http://release-assets.githubusercontent.com/file?sig=value",
                "https://release-assets.githubusercontent.com.evil.example/file?sig=value",
                "https://user@release-assets.githubusercontent.com/file?sig=value",
                "https://release-assets.githubusercontent.com:443/file?sig=value",
                "https://objects.githubusercontent.com/file?sig=value",
                "https://release-assets.githubusercontent.com/file?sig=value#fragment"
        );
        for (String url : rejectedUrls) {
            assertFalse(url, UpdatePolicy.isAllowedAssetRedirectUrl(url));
        }
    }

    @Test
    public void sha256ValuesAcceptGitHubPrefixAndNormalizeHexCase() {
        assertEquals(LOWERCASE_SHA256, UpdatePolicy.normalizeSha256(UPPERCASE_SHA256));
        assertEquals(
                LOWERCASE_SHA256,
                UpdatePolicy.normalizeSha256("  sha256:" + UPPERCASE_SHA256 + "  ")
        );
        assertNull(UpdatePolicy.normalizeSha256(null));
        assertNull(UpdatePolicy.normalizeSha256("sha256:" + repeat("a", 63)));
        assertNull(UpdatePolicy.normalizeSha256("sha512:" + UPPERCASE_SHA256));
        assertNull(UpdatePolicy.normalizeSha256(repeat("g", 64)));
    }

    @Test
    public void checksumFilesBindTheDigestToTheExactApkName() {
        assertEquals(
                LOWERCASE_SHA256,
                UpdatePolicy.parseChecksumFile(UPPERCASE_SHA256 + "  " + APK_NAME + "\n", APK_NAME)
        );
        assertEquals(
                LOWERCASE_SHA256,
                UpdatePolicy.parseChecksumFile(UPPERCASE_SHA256 + " *" + APK_NAME + "\n", APK_NAME)
        );
        assertEquals(
                LOWERCASE_SHA256,
                UpdatePolicy.parseChecksumFile("\nsha256:" + UPPERCASE_SHA256 + "\n", APK_NAME)
        );
        assertNull(UpdatePolicy.parseChecksumFile(
                UPPERCASE_SHA256 + "  EInkWiki-v1.2.4.apk\n",
                APK_NAME
        ));
        assertNull(UpdatePolicy.parseChecksumFile("not-a-digest  " + APK_NAME + "\n", APK_NAME));
        assertNull(UpdatePolicy.parseChecksumFile("\n\n", APK_NAME));
    }

    @Test
    public void apkSizeLimitAllowsCurrentNativeReleaseHeadroom() {
        assertEquals(128L * 1024L * 1024L, UpdatePolicy.MAX_APK_BYTES);
    }

    private static String repeat(String value, int count) {
        StringBuilder result = new StringBuilder(value.length() * count);
        for (int index = 0; index < count; index += 1) {
            result.append(value);
        }
        return result.toString();
    }
}
