package com.trs.documentparser;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Supported document formats and content types shared by ingestion and parsing. @author czhcc */
public final class DocumentFormats {
    private static final Map<String, Set<String>> TYPES = Map.ofEntries(
            Map.entry("text", Set.of("text/plain")),
            Map.entry("markdown", Set.of("text/plain", "text/markdown")),
            Map.entry("pdf", Set.of("application/pdf")),
            Map.entry("doc", Set.of("application/msword")),
            Map.entry("docx", Set.of("application/vnd.openxmlformats-officedocument.wordprocessingml.document")),
            Map.entry("xls", Set.of("application/vnd.ms-excel")),
            Map.entry("xlsx", Set.of("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")),
            Map.entry("ppt", Set.of("application/vnd.ms-powerpoint")),
            Map.entry("pptx", Set.of("application/vnd.openxmlformats-officedocument.presentationml.presentation")),
            Map.entry("odt", Set.of("application/vnd.oasis.opendocument.text")),
            Map.entry("ods", Set.of("application/vnd.oasis.opendocument.spreadsheet")),
            Map.entry("odp", Set.of("application/vnd.oasis.opendocument.presentation")),
            Map.entry("rtf", Set.of("application/rtf", "text/rtf")),
            Map.entry("html", Set.of("text/html", "application/xhtml+xml")),
            Map.entry("csv", Set.of("text/plain", "text/csv", "text/tsv", "text/tab-separated-values")));

    private DocumentFormats() {}

    /** Resolves a supported filename extension without reading file contents. @author czhcc */
    public static String fromFilename(String filename) {
        int dot = filename.lastIndexOf('.');
        if (dot < 0) throw new IllegalArgumentException("Unsupported file");
        String extension = filename.substring(dot + 1).toLowerCase(Locale.ROOT);
        String format = switch (extension) {
            case "txt" -> "text";
            case "md", "markdown" -> "markdown";
            case "htm" -> "html";
            default -> extension;
        };
        if (!supports(format)) throw new IllegalArgumentException("Unsupported file");
        return format;
    }

    /** Checks whether the format has an explicitly enabled parser contract. @author czhcc */
    public static boolean supports(String format) {
        return format != null && TYPES.containsKey(format);
    }

    /** Rejects detected content that does not match the submitted document format. @author czhcc */
    public static boolean matches(String format, String mediaType) {
        return supports(format) && TYPES.get(format).contains(mediaType);
    }
}
