package com.trs.app.rag.index;

import com.trs.app.rag.model.ModelFailure;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Port of pinned LightRAG record parsing, normalization and bounded glean replacement. @author
 * agent
 */
@Component
public class ExtractionParser {
  private static final Set<String> RESERVED =
      Set.of(
          "__proto__",
          "prototype",
          "constructor",
          "tostring",
          "valueof",
          "hasownproperty",
          "isprototypeof",
          "propertyisenumerable",
          "__definegetter__",
          "__definesetter__",
          "__lookupgetter__",
          "__lookupsetter__");

  /** Parses exact four/five-part records and rejects incomplete model responses. @author agent */
  public Extraction parse(String output) {
    if (output == null
        || output.length() > 2000000
        || !output.toUpperCase(Locale.ROOT).contains("<|COMPLETE|>"))
      throw new ModelFailure("extraction_incomplete");
    List<Extraction.Entity> entities = new ArrayList<>();
    List<Extraction.Relation> relations = new ArrayList<>();
    String text =
        output
            .replaceAll("(?i)" + Pattern.quote("<|COMPLETE|>"), "\n")
            .replaceAll(Pattern.quote("<|#|>") + "(?=entity" + Pattern.quote("<|#|>") + ")", "\n")
            .replaceAll(
                Pattern.quote("<|#|>")
                    + "(?=relations?hip"
                    + Pattern.quote("<|#|>")
                    + "|relation"
                    + Pattern.quote("<|#|>")
                    + ")",
                "\n");
    int rows = 0;
    for (String line : text.split("\\R")) {
      String[] p = line.strip().split(Pattern.quote("<|#|>"), -1);
      if (p.length == 4 && p[0].strip().equalsIgnoreCase("entity")) {
        String name = normalize(p[1], true),
            type = normalize(p[2], true),
            desc = normalize(p[3], false);
        if (type.matches(".*['()<>|/\\\\].*")) continue;
        type =
            Arrays.stream(type.split(","))
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .findFirst()
                .orElse("")
                .replace(" ", "")
                .toLowerCase(Locale.ROOT);
        if (!name.isEmpty()
            && name.length() <= 512
            && !type.isEmpty()
            && type.length() <= 100
            && !RESERVED.contains(type)
            && !desc.isEmpty()
            && desc.length() <= 16000) {
          entities.add(new Extraction.Entity(name, type, desc));
          rows++;
        }
      } else if (p.length == 5
          && (p[0].strip().equalsIgnoreCase("relation")
              || p[0].strip().equalsIgnoreCase("relationship"))) {
        String a = normalize(p[1], true),
            b = normalize(p[2], true),
            keywords = normalize(p[3], true).replace('，', ','),
            desc = normalize(p[4], false);
        if (!a.isEmpty()
            && !b.isEmpty()
            && !a.equals(b)
            && a.length() <= 512
            && b.length() <= 512
            && !desc.isEmpty()
            && desc.length() <= 16000
            && keywords.length() <= 4000) {
          if (a.compareTo(b) > 0) {
            String temp = a;
            a = b;
            b = temp;
          }
          relations.add(new Extraction.Relation(a, b, keywords, desc, 1));
          rows++;
        }
      }
      if (rows > 100 || entities.size() > 40) throw new ModelFailure("extraction_record_limit");
    }
    return glean(new Extraction(), new Extraction(entities, relations));
  }

  /**
   * Replaces a duplicate entity or edge only when the gleaned description is longer. @author agent
   */
  public Extraction glean(Extraction original, Extraction additional) {
    Map<String, Extraction.Entity> entities = new LinkedHashMap<>();
    for (var e : original.getEntities()) entities.put(e.getName(), e);
    for (var e : additional.getEntities())
      entities.merge(
          e.getName(),
          e,
          (a, b) -> b.getDescription().length() > a.getDescription().length() ? b : a);
    Map<List<String>, Extraction.Relation> relations = new LinkedHashMap<>();
    for (var r : original.getRelations()) relations.put(List.of(r.getSource(), r.getTarget()), r);
    for (var r : additional.getRelations())
      relations.merge(
          List.of(r.getSource(), r.getTarget()),
          r,
          (a, b) -> b.getDescription().length() > a.getDescription().length() ? b : a);
    return new Extraction(new ArrayList<>(entities.values()), new ArrayList<>(relations.values()));
  }

  /**
   * Applies upstream width, quote, Chinese-spacing and short numeric normalization. @author agent
   */
  public String normalize(String input, boolean innerQuotes) {
    String s =
        input
            .replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]", "")
            .replaceAll("(?i)</?p\\s*>|<p/>|</?br\\s*>|<br/>", "");
    StringBuilder width = new StringBuilder();
    for (char c : s.toCharArray())
      width.append(
          (c >= 'Ａ' && c <= 'Ｚ' || c >= 'ａ' && c <= 'ｚ' || c >= '０' && c <= '９')
              ? (char) (c - 0xfee0)
              : c);
    s =
        width
            .toString()
            .replace('－', '-')
            .replace('＋', '+')
            .replace('／', '/')
            .replace('＊', '*')
            .replace('（', '(')
            .replace('）', ')')
            .replace('—', '-')
            .replace('　', ' ');
    s =
        s.replaceAll("(?<=[\\u4e00-\\u9fa5])\\s+(?=[\\u4e00-\\u9fa5])", "")
            .replaceAll("(?<=[\\u4e00-\\u9fa5])\\s+(?=[a-zA-Z0-9()\\[\\]@#$%!&*\\-=+_])", "")
            .replaceAll("(?<=[a-zA-Z0-9()\\[\\]@#$%!&*\\-=+_])\\s+(?=[\\u4e00-\\u9fa5])", "");
    for (String pair : List.of("\"\"", "''", "“”", "‘’", "《》")) {
      if (s.length() >= 2
          && s.charAt(0) == pair.charAt(0)
          && s.charAt(s.length() - 1) == pair.charAt(1)) {
        String core = s.substring(1, s.length() - 1);
        if (core.indexOf(pair.charAt(0)) < 0 && core.indexOf(pair.charAt(1)) < 0) s = core;
      }
    }
    if (innerQuotes)
      s =
          s.replaceAll("[“”‘’]", "")
              .replaceAll("['\"]+(?=[\\u4e00-\\u9fa5])|(?<=[\\u4e00-\\u9fa5])['\"]+", "")
              .replace('\u00a0', ' ')
              .replaceAll("(?<=[^\\d])\\u202f", " ");
    s = s.strip();
    if (s.length() < 3 && s.matches("[0-9]+")
        || s.length() < 6 && s.contains(".") && s.matches("[0-9.]+")) return "";
    return s;
  }
}
