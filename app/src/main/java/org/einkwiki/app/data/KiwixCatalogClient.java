package org.einkwiki.app.data;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

/** Fetches the fixed official Kiwix OPDS feed and resolves exact Metalink metadata. */
public final class KiwixCatalogClient {
    private static final String ATOM_NAMESPACE = "http://www.w3.org/2005/Atom";
    private static final String METALINK_NAMESPACE = "urn:ietf:params:xml:ns:metalink";
    public static final String CATALOG_ENDPOINT =
            "https://opds.library.kiwix.org/catalog/v2/entries"
                    + "?lang=zho&category=wikipedia&count=-1";

    private static final String ACQUISITION_REL =
            "http://opds-spec.org/acquisition/open-access";
    private static final String ZIM_TYPE = "application/x-zim";
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 30_000;
    private static final int MAX_REDIRECTS = 3;
    private static final int MAX_CATALOG_BYTES = 4 * 1024 * 1024;
    private static final int MAX_METALINK_BYTES = 8 * 1024 * 1024;
    private static final Set<String> CATALOG_HOSTS = immutableSet(
            "opds.library.kiwix.org"
    );
    private static final Set<String> METALINK_HOSTS = immutableSet(
            "lb.download.kiwix.org",
            "download.kiwix.org"
    );
    private static final Set<String> FLAVOURS = immutableSet("mini", "nopic", "maxi");

    public List<OfflinePack> fetchCatalog() throws IOException {
        byte[] document = fetch(
                URI.create(CATALOG_ENDPOINT),
                CATALOG_HOSTS,
                MAX_CATALOG_BYTES,
                "application/atom+xml"
        );
        return parseCatalog(document);
    }

    /** Compatibility-friendly explicit name for the only catalog this client exposes. */
    public List<OfflinePack> fetchChineseWikipedia() throws IOException {
        return fetchCatalog();
    }

    public OfflinePack resolveDownloadMetadata(OfflinePack pack) throws IOException {
        if (pack == null) {
            throw new IllegalArgumentException("pack must not be null");
        }
        if (pack.hasDownloadMetadata()) {
            return pack;
        }
        URI metalink = validateMetalinkUri(pack.metalinkUrl);
        byte[] document = fetch(
                metalink,
                METALINK_HOSTS,
                MAX_METALINK_BYTES,
                "application/metalink4+xml, application/xml"
        );
        return parseMetalink(pack, document);
    }

    static List<OfflinePack> parseCatalog(byte[] xml) throws IOException {
        Document document = parseXml(xml);
        Element root = document.getDocumentElement();
        if (root == null
                || !"feed".equals(localName(root))
                || !ATOM_NAMESPACE.equals(root.getNamespaceURI())) {
            throw new IOException("Kiwix OPDS 根元素无效");
        }

        Map<String, OfflinePack> unique = new LinkedHashMap<>();
        for (Element entry : directChildren(root, "entry")) {
            OfflinePack parsed = parseCatalogEntry(entry);
            if (parsed != null) {
                OfflinePack current = unique.get(parsed.logicalId);
                if (current == null || parsed.version.compareTo(current.version) > 0) {
                    unique.put(parsed.logicalId, parsed);
                }
            }
        }
        if (unique.isEmpty()) {
            throw new IOException("Kiwix OPDS 中没有可用的中文维基百科包");
        }
        return Collections.unmodifiableList(new ArrayList<>(unique.values()));
    }

    static OfflinePack parseMetalink(OfflinePack pack, byte[] xml) throws IOException {
        Document document = parseXml(xml);
        Element root = document.getDocumentElement();
        if (root == null
                || !"metalink".equals(localName(root))
                || !METALINK_NAMESPACE.equals(root.getNamespaceURI())) {
            throw new IOException("Kiwix Metalink 根元素无效");
        }

        Element selected = null;
        for (Element file : directChildren(root, "file")) {
            if (pack.fileName.equals(file.getAttribute("name"))) {
                if (selected != null) {
                    throw new IOException("Kiwix Metalink 包含重复的目标文件");
                }
                selected = file;
            }
        }
        if (selected == null) {
            throw new IOException("Kiwix Metalink 与目录文件名不匹配");
        }

        long size = parsePositiveLong(directText(selected, "size"), "Metalink size");
        String sha256 = "";
        for (Element hash : directChildren(selected, "hash")) {
            String type = hash.getAttribute("type").trim().toLowerCase(Locale.ROOT);
            if ("sha-256".equals(type) || "sha256".equals(type)) {
                String candidate = text(hash).toLowerCase(Locale.ROOT);
                if (!candidate.matches("[0-9a-f]{64}")) {
                    throw new IOException("Kiwix Metalink SHA-256 无效");
                }
                if (!sha256.isEmpty() && !sha256.equals(candidate)) {
                    throw new IOException("Kiwix Metalink 包含冲突的 SHA-256");
                }
                sha256 = candidate;
            }
        }
        if (sha256.isEmpty()) {
            throw new IOException("Kiwix Metalink 缺少 SHA-256");
        }

        String downloadUrl = preferredMirrorUrl(selected, pack.fileName);
        if (!downloadUrl.isEmpty()) {
            return pack.withDownloadMetadata(downloadUrl, size, sha256);
        }

        URI metalink = validateMetalinkUri(pack.metalinkUrl);
        String path = metalink.getRawPath();
        String expectedSuffix = pack.fileName + ".meta4";
        if (path == null || !path.endsWith("/" + expectedSuffix)) {
            throw new IOException("Kiwix Metalink URL 与目录文件名不匹配");
        }
        String downloadPath = path.substring(0, path.length() - ".meta4".length());
        String fallbackUrl;
        try {
            fallbackUrl = new URI(
                    "https",
                    null,
                    metalink.getHost(),
                    -1,
                    downloadPath,
                    null,
                    null
            )
                    .toASCIIString();
        } catch (URISyntaxException impossible) {
            throw new IOException("无法构造 Kiwix 下载地址", impossible);
        }
        return pack.withDownloadMetadata(fallbackUrl, size, sha256);
    }

    private static OfflinePack parseCatalogEntry(Element entry) {
        try {
            if (!"zho".equals(directText(entry, "language"))
                    || !"wikipedia".equals(directText(entry, "category"))
                    || !directText(entry, "tags").contains("_ftindex:yes")) {
                return null;
            }
            String name = directText(entry, "name");
            String flavour = directText(entry, "flavour").toLowerCase(Locale.ROOT);
            if (!name.matches("wikipedia_zh_[a-z0-9_]+") || !FLAVOURS.contains(flavour)) {
                return null;
            }

            Element acquisition = null;
            for (Element link : directChildren(entry, "link")) {
                if (ACQUISITION_REL.equals(link.getAttribute("rel").trim())
                        && ZIM_TYPE.equalsIgnoreCase(link.getAttribute("type").trim())) {
                    acquisition = link;
                    break;
                }
            }
            if (acquisition == null) {
                return null;
            }
            URI metalink = validateMetalinkUri(acquisition.getAttribute("href"));
            String fileName = fileNameFromMetalink(metalink);
            String artifactId = fileName.substring(0, fileName.length() - ".zim".length());
            String logicalId = name + "_" + flavour;
            String prefix = logicalId + "_";
            if (!artifactId.startsWith(prefix)) {
                return null;
            }
            String version = artifactId.substring(prefix.length());
            if (!version.matches("[0-9]{4}-[0-9]{2}[a-z]?")) {
                return null;
            }

            long articleCount = parseOptionalNonNegativeLong(
                    directText(entry, "articleCount")
            );
            long advertisedBytes = parseOptionalPositiveLong(
                    acquisition.getAttribute("length")
            );
            return OfflinePack.unresolved(
                    logicalId,
                    artifactId,
                    directText(entry, "title"),
                    directText(entry, "summary"),
                    flavour,
                    articleCount,
                    version,
                    fileName,
                    metalink.toASCIIString(),
                    advertisedBytes
            );
        } catch (IOException | IllegalArgumentException ignored) {
            // The feed is remotely managed. One malformed entry must not hide valid siblings.
            return null;
        }
    }

    private static URI validateMetalinkUri(String value) throws IOException {
        URI uri;
        try {
            uri = new URI(value == null ? "" : value.trim());
        } catch (URISyntaxException error) {
            throw new IOException("Kiwix Metalink URL 无效", error);
        }
        validateHttpsUri(uri, METALINK_HOSTS);
        String path = uri.getRawPath();
        if (path == null
                || !path.startsWith("/zim/wikipedia/")
                || !path.endsWith(".zim.meta4")
                || path.contains("..")
                || path.indexOf('%') >= 0
                || uri.getRawQuery() != null) {
            throw new IOException("Kiwix Metalink 路径不受支持");
        }
        return uri;
    }

    private static String fileNameFromMetalink(URI metalink) throws IOException {
        String path = metalink.getPath();
        int slash = path.lastIndexOf('/');
        String metaName = slash < 0 ? path : path.substring(slash + 1);
        if (!metaName.matches("[A-Za-z0-9][A-Za-z0-9._-]*\\.zim\\.meta4")) {
            throw new IOException("Kiwix Metalink 文件名无效");
        }
        return metaName.substring(0, metaName.length() - ".meta4".length());
    }

    private static String preferredMirrorUrl(Element file, String expectedFileName) {
        String selected = "";
        int selectedPriority = Integer.MAX_VALUE;
        for (Element urlElement : directChildren(file, "url")) {
            String value = text(urlElement);
            try {
                URI uri = new URI(value);
                String path = uri.getRawPath();
                if (!"https".equalsIgnoreCase(uri.getScheme())
                        || uri.getHost() == null
                        || uri.getUserInfo() != null
                        || uri.getFragment() != null
                        || uri.getRawQuery() != null
                        || (uri.getPort() != -1 && uri.getPort() != 443)
                        || path == null
                        || path.indexOf('%') >= 0
                        || !path.endsWith("/" + expectedFileName)) {
                    continue;
                }
                int priority = parsePriority(urlElement.getAttribute("priority"));
                if (selected.isEmpty() || priority < selectedPriority) {
                    selected = uri.toASCIIString();
                    selectedPriority = priority;
                }
            } catch (URISyntaxException ignored) {
                // Ignore an invalid mirror and consider the remaining official entries.
            }
        }
        return selected;
    }

    private static int parsePriority(String value) {
        try {
            int priority = Integer.parseInt(value == null ? "" : value.trim());
            return priority > 0 ? priority : Integer.MAX_VALUE;
        } catch (NumberFormatException ignored) {
            return Integer.MAX_VALUE;
        }
    }

    private static byte[] fetch(
            URI initial,
            Set<String> allowedHosts,
            int maxBytes,
            String accept
    ) throws IOException {
        URI current = initial;
        for (int redirect = 0; redirect <= MAX_REDIRECTS; redirect++) {
            validateHttpsUri(current, allowedHosts);
            HttpURLConnection connection = (HttpURLConnection) new URL(current.toASCIIString())
                    .openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestProperty("Accept", accept);
            connection.setRequestProperty("User-Agent", "EInkWiki-catalog/1");
            try {
                int status = connection.getResponseCode();
                if (status >= 300 && status < 400) {
                    if (redirect == MAX_REDIRECTS) {
                        throw new IOException("Kiwix 服务重定向次数过多");
                    }
                    String location = connection.getHeaderField("Location");
                    if (location == null || location.trim().isEmpty()) {
                        throw new IOException("Kiwix 服务返回了无目标重定向");
                    }
                    current = current.resolve(location.trim());
                    continue;
                }
                if (status != HttpURLConnection.HTTP_OK) {
                    throw new IOException("Kiwix 服务返回 HTTP " + status);
                }
                long declared = connection.getContentLengthLong();
                if (declared > maxBytes) {
                    throw new IOException("Kiwix 元数据响应过大");
                }
                try (InputStream input = connection.getInputStream()) {
                    return readBounded(input, maxBytes);
                }
            } finally {
                connection.disconnect();
            }
        }
        throw new IOException("Kiwix 服务重定向失败");
    }

    private static byte[] readBounded(InputStream input, int maxBytes) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maxBytes, 64 * 1024));
        byte[] buffer = new byte[16 * 1024];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > maxBytes) {
                throw new IOException("Kiwix 元数据响应过大");
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static void validateHttpsUri(URI uri, Set<String> allowedHosts) throws IOException {
        String host = uri.getHost();
        if (!"https".equalsIgnoreCase(uri.getScheme())
                || host == null
                || !allowedHosts.contains(host.toLowerCase(Locale.ROOT))
                || uri.getUserInfo() != null
                || uri.getFragment() != null
                || (uri.getPort() != -1 && uri.getPort() != 443)) {
            throw new IOException("Kiwix 元数据地址不受信任");
        }
    }

    private static Document parseXml(byte[] xml) throws IOException {
        if (xml == null || xml.length == 0) {
            throw new IOException("Kiwix 元数据为空");
        }
        // Android's platform DocumentBuilder does not consistently expose the Xerces feature
        // names available on a desktop JDK. Reject declarations before parsing instead: without
        // a DOCTYPE, XML general/external entities cannot be declared at all.
        String declarationScan;
        try {
            declarationScan = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(xml))
                    .toString()
                    .toUpperCase(Locale.ROOT);
        } catch (CharacterCodingException error) {
            throw new IOException("无法安全解析 Kiwix XML：仅支持 UTF-8", error);
        }
        if (declarationScan.contains("<!DOCTYPE") || declarationScan.contains("<!ENTITY")) {
            throw new IOException("无法安全解析 Kiwix XML：禁止 DTD 和实体声明");
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setEntityResolver((publicId, systemId) -> new InputSource(new StringReader("")));
            return builder.parse(new ByteArrayInputStream(xml));
        } catch (ParserConfigurationException | SAXException | RuntimeException error) {
            throw new IOException("无法安全解析 Kiwix XML", error);
        }
    }

    private static List<Element> directChildren(Element parent, String name) {
        List<Element> result = new ArrayList<>();
        NodeList children = parent.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            Node child = children.item(index);
            if (child instanceof Element
                    && name.equals(localName(child))
                    && namespacesEqual(parent.getNamespaceURI(), child.getNamespaceURI())) {
                result.add((Element) child);
            }
        }
        return result;
    }

    private static String directText(Element parent, String name) {
        List<Element> matches = directChildren(parent, name);
        return matches.isEmpty() ? "" : text(matches.get(0));
    }

    private static String text(Element element) {
        String value = element.getTextContent();
        return value == null ? "" : value.trim();
    }

    private static String localName(Node node) {
        String local = node.getLocalName();
        return local == null ? node.getNodeName() : local;
    }

    private static boolean namespacesEqual(String first, String second) {
        return first == null ? second == null : first.equals(second);
    }

    private static long parsePositiveLong(String value, String field) throws IOException {
        try {
            long parsed = Long.parseLong(value.trim());
            if (parsed <= 0L) {
                throw new NumberFormatException();
            }
            return parsed;
        } catch (NumberFormatException error) {
            throw new IOException(field + " 无效", error);
        }
    }

    private static long parseOptionalPositiveLong(String value) {
        if (value == null || value.trim().isEmpty()) {
            return -1L;
        }
        try {
            long parsed = Long.parseLong(value.trim());
            return parsed > 0L ? parsed : -1L;
        } catch (NumberFormatException ignored) {
            return -1L;
        }
    }

    private static long parseOptionalNonNegativeLong(String value) {
        if (value == null || value.trim().isEmpty()) {
            return -1L;
        }
        try {
            long parsed = Long.parseLong(value.trim());
            return parsed >= 0L ? parsed : -1L;
        } catch (NumberFormatException ignored) {
            return -1L;
        }
    }

    private static Set<String> immutableSet(String... values) {
        Set<String> result = new HashSet<>();
        Collections.addAll(result, values);
        return Collections.unmodifiableSet(result);
    }
}
