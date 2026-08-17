package org.einkwiki.app.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

public final class KiwixCatalogClientTest {
    @Test
    public void parsesAndLocallyFiltersOfficialOpdsEntries() throws Exception {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<feed xmlns=\"http://www.w3.org/2005/Atom\">"
                + entry(
                "zho",
                "wikipedia",
                "wikipedia_zh_chemistry",
                "nopic",
                "化学维基百科",
                "在化学维基百科文章的选择",
                "11874",
                "https://lb.download.kiwix.org/zim/wikipedia/"
                        + "wikipedia_zh_chemistry_nopic_2026-06.zim.meta4",
                "43884544"
        )
                + entry(
                "eng",
                "wikipedia",
                "wikipedia_en_all",
                "maxi",
                "Wikipedia",
                "English",
                "100",
                "https://lb.download.kiwix.org/zim/wikipedia/"
                        + "wikipedia_en_all_maxi_2026-06.zim.meta4",
                "1000"
        )
                + entry(
                "zho",
                "wikibooks",
                "wikipedia_zh_fake",
                "maxi",
                "错误分类",
                "错误分类",
                "100",
                "https://lb.download.kiwix.org/zim/wikipedia/"
                        + "wikipedia_zh_fake_maxi_2026-06.zim.meta4",
                "1000"
        )
                + entry(
                "zho",
                "wikipedia",
                "wikipedia_zh_unsafe",
                "maxi",
                "不安全地址",
                "不安全地址",
                "100",
                "https://example.com/wikipedia_zh_unsafe_maxi_2026-06.zim.meta4",
                "1000"
        )
                + "</feed>";

        List<OfflinePack> packs = KiwixCatalogClient.parseCatalog(bytes(xml));

        assertEquals(1, packs.size());
        OfflinePack pack = packs.get(0);
        assertEquals("wikipedia_zh_chemistry_nopic", pack.logicalId);
        assertEquals("wikipedia_zh_chemistry_nopic_2026-06", pack.artifactId());
        assertEquals("化学维基百科", pack.title);
        assertEquals("在化学维基百科文章的选择", pack.description);
        assertEquals("nopic", pack.flavour);
        assertEquals(11_874L, pack.articleCount);
        assertEquals(43_884_544L, pack.advertisedBytes);
        assertEquals(-1L, pack.expectedBytes);
        assertFalse(pack.hasDownloadMetadata());
    }

    @Test
    public void resolvesExactSizeShaAndOfficialDownloadUrlFromMeta4() throws Exception {
        OfflinePack unresolved = unresolvedChemistry();
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<metalink xmlns=\"urn:ietf:params:xml:ns:metalink\">"
                + "<file name=\"wikipedia_zh_chemistry_nopic_2026-06.zim\">"
                + "<size>43883567</size>"
                + "<hash type=\"md5\">00000000000000000000000000000000</hash>"
                + "<hash type=\"sha-256\">"
                + "3a25f1e50da3f20d5c63bb54fdb7cfaf0d5af03656d7fc83511bd300bf9dbbbd"
                + "</hash>"
                + "<pieces type=\"sha-256\"><hash>"
                + "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"
                + "</hash></pieces>"
                + "<url priority=\"2\">https://mirror-two.example/zim/"
                + "wikipedia_zh_chemistry_nopic_2026-06.zim</url>"
                + "<url priority=\"1\">https://mirror-one.example/zim/"
                + "wikipedia_zh_chemistry_nopic_2026-06.zim</url>"
                + "</file></metalink>";

        OfflinePack resolved = KiwixCatalogClient.parseMetalink(unresolved, bytes(xml));

        assertTrue(resolved.hasDownloadMetadata());
        assertEquals(43_883_567L, resolved.expectedBytes);
        assertEquals(
                "3a25f1e50da3f20d5c63bb54fdb7cfaf0d5af03656d7fc83511bd300bf9dbbbd",
                resolved.sha256
        );
        assertEquals(
                "https://mirror-one.example/zim/"
                        + "wikipedia_zh_chemistry_nopic_2026-06.zim",
                resolved.downloadUrl
        );
    }

    @Test
    public void keepsOnlyNewestArtifactForEachScopeAndFlavour() throws Exception {
        String older = "https://lb.download.kiwix.org/zim/wikipedia/"
                + "wikipedia_zh_chemistry_nopic_2026-05.zim.meta4";
        String newer = "https://lb.download.kiwix.org/zim/wikipedia/"
                + "wikipedia_zh_chemistry_nopic_2026-06.zim.meta4";
        String xml = "<feed xmlns=\"http://www.w3.org/2005/Atom\">"
                + entry("zho", "wikipedia", "wikipedia_zh_chemistry", "nopic",
                "化学", "旧版", "10", older, "100")
                + entry("zho", "wikipedia", "wikipedia_zh_chemistry", "nopic",
                "化学", "新版", "11", newer, "110")
                + "</feed>";

        List<OfflinePack> packs = KiwixCatalogClient.parseCatalog(bytes(xml));

        assertEquals(1, packs.size());
        assertEquals("2026-06", packs.get(0).version);
        assertEquals("新版", packs.get(0).description);
    }

    @Test
    public void rejectsDoctypeAndMismatchedMetalinkFiles() throws Exception {
        try {
            KiwixCatalogClient.parseCatalog(bytes(
                    "<!DOCTYPE feed [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]>"
                            + "<feed xmlns=\"http://www.w3.org/2005/Atom\">"
                            + "<title>&xxe;</title></feed>"
            ));
            fail("DOCTYPE must be rejected");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("解析"));
        }

        String wrongFile = "<metalink xmlns=\"urn:ietf:params:xml:ns:metalink\">"
                + "<file name=\"other.zim\"><size>1</size>"
                + "<hash type=\"sha-256\">"
                + "0000000000000000000000000000000000000000000000000000000000000000"
                + "</hash></file></metalink>";
        try {
            KiwixCatalogClient.parseMetalink(unresolvedChemistry(), bytes(wrongFile));
            fail("mismatched file must be rejected");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("文件名"));
        }
    }

    private static OfflinePack unresolvedChemistry() {
        return OfflinePack.unresolved(
                "wikipedia_zh_chemistry_nopic",
                "wikipedia_zh_chemistry_nopic_2026-06",
                "化学维基百科",
                "在化学维基百科文章的选择",
                "nopic",
                11_874L,
                "2026-06",
                "wikipedia_zh_chemistry_nopic_2026-06.zim",
                "https://lb.download.kiwix.org/zim/wikipedia/"
                        + "wikipedia_zh_chemistry_nopic_2026-06.zim.meta4",
                43_884_544L
        );
    }

    private static String entry(
            String language,
            String category,
            String name,
            String flavour,
            String title,
            String summary,
            String articleCount,
            String href,
            String length
    ) {
        return "<entry><title>" + title + "</title><summary>" + summary + "</summary>"
                + "<language>" + language + "</language><category>" + category + "</category>"
                + "<tags>_ftindex:yes</tags>"
                + "<name>" + name + "</name><flavour>" + flavour + "</flavour>"
                + "<articleCount>" + articleCount + "</articleCount>"
                + "<link rel=\"http://opds-spec.org/acquisition/open-access\" "
                + "type=\"application/x-zim\" href=\"" + href + "\" length=\""
                + length + "\"/></entry>";
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
