package com.terafiles;

import android.graphics.Typeface;
import android.text.Editable;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lightweight, dependency-free syntax highlighter (DroidPad++ style).
 * Built entirely on android.text.Spannable + java.util.regex - no third
 * party libraries required.
 *
 * Each supported language is described as a single combined regular
 * expression with named capturing groups (one group per token category).
 * On every highlight pass the whole visible document is scanned once and
 * colour/style spans are applied for each match.
 */
public class EditorHighlighter {

	// ---- Token category group names shared by every language pattern ----
	private static final String G_COMMENT = "COMMENT";
	private static final String G_STRING = "STRING";
	private static final String G_NUMBER = "NUMBER";
	private static final String G_KEYWORD = "KEYWORD";
	private static final String G_TYPE = "TYPE";
	private static final String G_ANNOT = "ANNOT";
	private static final String G_TAG = "TAG";
	private static final String G_ATTR = "ATTR";

	private static final String[] GROUPS_CLIKE = new String[]{G_COMMENT, G_STRING, G_ANNOT, G_NUMBER, G_KEYWORD};
	private static final String[] GROUPS_SHELL = new String[]{G_COMMENT, G_STRING, G_NUMBER, G_KEYWORD, G_TYPE};
	private static final String[] GROUPS_SQL = new String[]{G_COMMENT, G_STRING, G_NUMBER, G_KEYWORD};
	private static final String[] GROUPS_HTML = new String[]{G_COMMENT, G_TAG, G_STRING, G_ATTR};
	private static final String[] GROUPS_CSS = new String[]{G_COMMENT, G_STRING, G_NUMBER, G_ATTR, G_KEYWORD};
	private static final String[] GROUPS_JSON = new String[]{G_STRING, G_NUMBER, G_KEYWORD};
	private static final String[] GROUPS_YAML = new String[]{G_COMMENT, G_STRING, G_KEYWORD, G_ATTR};
	private static final String[] GROUPS_MD = new String[]{G_COMMENT, G_KEYWORD, G_STRING, G_ANNOT, G_ATTR};

	// ---- Marker span classes: lets us remove only spans WE added ----
	private static final class HlColor extends ForegroundColorSpan {
		HlColor(int color) {
			super(color);
		}
	}

	private static final class HlBold extends StyleSpan {
		HlBold() {
			super(Typeface.BOLD);
		}
	}

	private static final class HlItalic extends StyleSpan {
		HlItalic() {
			super(Typeface.ITALIC);
		}
	}

	private static final class LangDef {
		final String id;
		final String displayName;
		final String[] extensions;
		final Pattern pattern;
		final String[] groups;

		LangDef(String id, String displayName, String[] extensions, Pattern pattern, String[] groups) {
			this.id = id;
			this.displayName = displayName;
			this.extensions = extensions;
			this.pattern = pattern;
			this.groups = groups;
		}
	}

	private static final Map<String, LangDef> LANGS = new HashMap<String, LangDef>();
	private static final Map<String, String> EXT_TO_LANG = new HashMap<String, String>();
	private static final ArrayList<String> ORDER = new ArrayList<String>();

	// Safety cap: skip highlighting past this many characters to avoid ANRs
	// on huge files (the whole point of the debounce is to keep typing
	// smooth; a multi-megabyte file is rare in a mobile text editor).
	private static final int MAX_HIGHLIGHT_LENGTH = 400000;

	static {
		registerCLike("java", "Java", new String[]{"java"},
				"abstract assert boolean break byte case catch char class const continue default do double else "
						+ "enum extends final finally float for goto if implements import instanceof int interface "
						+ "long native new package private protected public return short static strictfp super "
						+ "switch synchronized this throw throws transient try void volatile while true false null");
		registerCLike("kotlin", "Kotlin", new String[]{"kt", "kts"},
				"as break class continue do else false for fun if in interface is null object package return super "
						+ "this throw true try typealias val var when while by companion constructor init internal "
						+ "lateinit open override private protected public sealed data");
		registerCLike("c", "C", new String[]{"c", "h"},
				"auto break case char const continue default do double else enum extern float for goto if int long "
						+ "register return short signed sizeof static struct switch typedef union unsigned void "
						+ "volatile while");
		registerCLike("cpp", "C++", new String[]{"cpp", "cc", "cxx", "hpp", "hh"},
				"alignas alignof and asm auto bitand bitor bool break case catch char class const constexpr "
						+ "const_cast continue decltype default delete do double dynamic_cast else enum explicit "
						+ "export extern false float for friend goto if inline int long mutable namespace new "
						+ "noexcept not nullptr operator or private protected public register reinterpret_cast "
						+ "return short signed sizeof static static_assert static_cast struct switch template this "
						+ "thread_local throw true try typedef typeid typename union unsigned using virtual void "
						+ "volatile wchar_t while");
		registerCLike("csharp", "C#", new String[]{"cs"},
				"abstract as base bool break byte case catch char checked class const continue decimal default "
						+ "delegate do double else enum event explicit extern false finally fixed float for foreach "
						+ "goto if implicit in int interface internal is lock long namespace new null object "
						+ "operator out override params private protected public readonly ref return sbyte sealed "
						+ "short sizeof stackalloc static string struct switch this throw true try typeof uint "
						+ "ulong unchecked unsafe ushort using virtual void volatile while var async await");
		registerCLike("javascript", "JavaScript", new String[]{"js", "jsx", "mjs"},
				"break case catch class const continue debugger default delete do else export extends finally for "
						+ "function if import in instanceof let new return super switch this throw try typeof var "
						+ "void while with yield async await true false null undefined");
		registerCLike("typescript", "TypeScript", new String[]{"ts", "tsx"},
				"break case catch class const continue debugger default delete do else export extends finally for "
						+ "function if implements import in instanceof interface let new package private protected "
						+ "public return static super switch this throw try type typeof var void while with yield "
						+ "async await true false null undefined enum namespace declare readonly as from of");
		registerCLike("swift", "Swift", new String[]{"swift"},
				"associatedtype class deinit enum extension fallthrough fileprivate func import init inout internal "
						+ "let open operator private protocol public rethrows static struct subscript typealias var "
						+ "break case continue default defer do else for guard if in repeat return switch where "
						+ "while as Any catch false is nil super self Self throw throws true try");
		registerCLike("go", "Go", new String[]{"go"},
				"break case chan const continue default defer else fallthrough for func go goto if import interface "
						+ "map package range return select struct switch type var true false nil iota");
		registerCLike("rust", "Rust", new String[]{"rs"},
				"as break const continue crate else enum extern false fn for if impl in let loop match mod move mut "
						+ "pub ref return self Self static struct super trait true type unsafe use where while "
						+ "async await dyn");
		registerCLike("php", "PHP", new String[]{"php"},
				"abstract and array as break callable case catch class clone const continue declare default do echo "
						+ "else elseif empty enddeclare endfor endforeach endif endswitch endwhile extends final "
						+ "finally fn for foreach function global goto if implements include include_once "
						+ "instanceof insteadof interface isset list match namespace new or print private protected "
						+ "public require require_once return static switch throw trait try unset use var while "
						+ "xor yield true false null");

		registerPython();
		registerShell();
		registerSql();
		registerHtml();
		registerCss();
		registerJson();
		registerYaml();
		registerMarkdown();
		registerToml();
		registerDockerfile();

		for (LangDef def : LANGS.values()) {
			for (String ext : def.extensions) {
				EXT_TO_LANG.put(ext, def.id);
			}
		}
	}

	private static void registerCLike(String id, String name, String[] exts, String keywords) {
		String kw = keywordAlternation(keywords);
		String pattern = "(?<" + G_COMMENT + ">//[^\\n]*|/\\*[\\s\\S]*?\\*/)"
				+ "|(?<" + G_STRING + ">\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*')"
				+ "|(?<" + G_ANNOT + ">@\\w+)"
				+ "|(?<" + G_NUMBER + ">\\b0[xX][0-9a-fA-F]+\\b|\\b\\d+\\.?\\d*(?:[eE][+-]?\\d+)?[fFdDlLuU]?\\b)"
				+ "|(?<" + G_KEYWORD + ">\\b(?:" + kw + ")\\b)";
		register(id, name, exts, pattern, GROUPS_CLIKE, false);
	}

	private static void registerPython() {
		String kw = keywordAlternation("False None True and as assert async await break class continue def del elif "
				+ "else except finally for from global if import in is lambda nonlocal not or pass raise return try "
				+ "while with yield self");
		String pattern = "(?<" + G_COMMENT + ">#[^\\n]*)"
				+ "|(?<" + G_STRING + ">'''[\\s\\S]*?'''|\"\"\"[\\s\\S]*?\"\"\"|\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*')"
				+ "|(?<" + G_ANNOT + ">@\\w+)"
				+ "|(?<" + G_NUMBER + ">\\b0[xX][0-9a-fA-F]+\\b|\\b\\d+\\.?\\d*(?:[eE][+-]?\\d+)?\\b)"
				+ "|(?<" + G_KEYWORD + ">\\b(?:" + kw + ")\\b)";
		register("python", "Python", new String[]{"py", "pyw"}, pattern, GROUPS_CLIKE, false);
	}

	private static void registerShell() {
		String kw = keywordAlternation("if then else elif fi for while until do done case esac function return in "
				+ "echo exit break continue local export readonly source select time");
		String pattern = "(?<" + G_COMMENT + ">#[^\\n]*)"
				+ "|(?<" + G_STRING + ">\"(?:\\\\.|[^\"\\\\])*\"|'[^']*')"
				+ "|(?<" + G_TYPE + ">\\$\\{[^}]*\\}|\\$\\w+)"
				+ "|(?<" + G_NUMBER + ">\\b\\d+\\b)"
				+ "|(?<" + G_KEYWORD + ">\\b(?:" + kw + ")\\b)";
		register("shell", "Shell", new String[]{"sh", "bash", "zsh"}, pattern, GROUPS_SHELL, false);
	}

	private static void registerSql() {
		String kw = keywordAlternation("select insert update delete from where join inner left right outer on group "
				+ "by order having as into values set create table alter drop index view database use null not and "
				+ "or in like between is distinct limit offset union all case when then end exists default primary "
				+ "key foreign references");
		String pattern = "(?<" + G_COMMENT + ">--[^\\n]*|/\\*[\\s\\S]*?\\*/)"
				+ "|(?<" + G_STRING + ">'(?:''|[^'])*')"
				+ "|(?<" + G_NUMBER + ">\\b\\d+\\.?\\d*\\b)"
				+ "|(?<" + G_KEYWORD + ">\\b(?:" + kw + ")\\b)";
		register("sql", "SQL", new String[]{"sql"}, pattern, GROUPS_SQL, true);
	}

	private static void registerHtml() {
		String pattern = "(?<" + G_COMMENT + "><!--[\\s\\S]*?--" + ">)"
				+ "|(?<" + G_TAG + "></?[a-zA-Z][\\w:.-]*)"
				+ "|(?<" + G_STRING + ">\"[^\"]*\"|'[^']*')"
				+ "|(?<" + G_ATTR + ">\\b[a-zA-Z-][\\w-]*(?=\\s*=))";
		register("html", "HTML / XML", new String[]{"html", "htm", "xml", "xhtml", "svg"}, pattern, GROUPS_HTML,
				false);
	}

	private static void registerCss() {
		String pattern = "(?<" + G_COMMENT + ">/\\*[\\s\\S]*?\\*/)"
				+ "|(?<" + G_STRING + ">\"[^\"]*\"|'[^']*')"
				+ "|(?<" + G_NUMBER + ">#[0-9a-fA-F]{3,8}\\b|\\b\\d+\\.?\\d*(?:px|em|rem|%|vh|vw|s|ms)?\\b)"
				+ "|(?<" + G_ATTR + ">[a-zA-Z-]+(?=\\s*:))"
				+ "|(?<" + G_KEYWORD + ">[.#][a-zA-Z][\\w-]*)";
		register("css", "CSS", new String[]{"css"}, pattern, GROUPS_CSS, false);
	}

	private static void registerJson() {
		String pattern = "(?<" + G_STRING + ">\"(?:\\\\.|[^\"\\\\])*\")"
				+ "|(?<" + G_NUMBER + ">-?\\b\\d+\\.?\\d*(?:[eE][+-]?\\d+)?\\b)"
				+ "|(?<" + G_KEYWORD + ">\\btrue\\b|\\bfalse\\b|\\bnull\\b)";
		register("json", "JSON", new String[]{"json"}, pattern, GROUPS_JSON, false);
	}

	private static void registerYaml() {
		String pattern = "(?<" + G_COMMENT + ">#[^\\n]*)"
				+ "|(?<" + G_STRING + ">\"(?:\\\\.|[^\"\\\\])*\"|'(?:[^']|'')*')"
				+ "|(?<" + G_KEYWORD + ">\\btrue\\b|\\bfalse\\b|\\bnull\\b|\\byes\\b|\\bno\\b)"
				+ "|(?<" + G_ATTR + ">^[ \\t]*[\\w.-]+(?=\\s*:))";
		register("yaml", "YAML", new String[]{"yml", "yaml"}, pattern, GROUPS_YAML, false);
	}

	private static void registerMarkdown() {
		String pattern = "(?<" + G_COMMENT + "><!--[\\s\\S]*?--" + ">)"
				+ "|(?<" + G_KEYWORD + ">^#{1,6}[ \\t].*$)"
				+ "|(?<" + G_STRING + ">```[\\s\\S]*?```|`[^`\\n]*`)"
				+ "|(?<" + G_ANNOT + ">\\*\\*[^*\\n]+\\*\\*|__[^_\\n]+__)"
				+ "|(?<" + G_ATTR + ">\\[[^\\]\\n]*\\]\\([^)\\n]*\\))";
		register("markdown", "Markdown", new String[]{"md", "markdown"}, pattern, GROUPS_MD, false);
	}


	private static void registerToml() {
		String pattern = "(?<" + G_COMMENT + ">#[^\\n]*)"
				+ "|(?<" + G_STRING + ">\"(?:\\\\.|[^\"\\\\])*\"|'(?:[^']|'')*')"
				+ "|(?<" + G_KEYWORD + ">\\btrue\\b|\\bfalse\\b|\\bnull\\b|\\byes\\b|\\bno\\b)"
				+ "|(?<" + G_ATTR + ">^[ \\t]*[\\w.-]+(?=\\s*:))";
		register("toml", "TOML", new String[]{"toml"}, pattern, GROUPS_YAML, false);
	}


	private static void registerDockerfile() {
		String kw = keywordAlternation("FROM AS RUN CMD LABEL MAINTAINER EXPOSE ENV ADD COPY ENTRYPOINT VOLUME USER "
				+ "WORKDIR ARG ONBUILD STOPSIGNAL HEALTHCHECK SHELL");
		String pattern = "(?<" + G_COMMENT + ">#[^\\n]*)"
				+ "|(?<" + G_STRING + ">\"(?:\\\\.|[^\"\\\\])*\"|'[^']*')"
				+ "|(?<" + G_TYPE + ">\\$\\{[^}]*\\}|\\$\\w+)"
				+ "|(?<" + G_NUMBER + ">\\b\\d+\\b)"
				+ "|(?<" + G_KEYWORD + ">\\b(?:" + kw + ")\\b)";
		register("dockerfile", "Dockerfile", new String[]{"dockerfile"}, pattern, GROUPS_SHELL, true);
	}


	private static void register(String id, String name, String[] exts, String patternSrc, String[] groups,
			boolean caseInsensitive) {
		try {
			int flags = Pattern.MULTILINE;
			if (caseInsensitive) {
				flags |= Pattern.CASE_INSENSITIVE;
			}
			Pattern p = Pattern.compile(patternSrc, flags);
			LangDef def = new LangDef(id, name, exts, p, groups);
			LANGS.put(id, def);
			ORDER.add(id);
		} catch (Exception e) {
			// Skip broken language definition rather than crashing the app at load time
			android.util.Log.e("EditorHighlighter", "Failed to register language: " + id, e);
		}
	}

	private static String keywordAlternation(String words) {
		String[] arr = words.trim().split("\\s+");
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < arr.length; i++) {
			if (i > 0) {
				sb.append('|');
			}
			sb.append(Pattern.quote(arr[i]));
		}
		return sb.toString();
	}

	/**
	 * Detects a language id from a file name (looking at its extension).
	 * Returns "text" (Plain Text - no highlighting) when unknown.
	 */
	public static String detectLanguage(String filename) {
		if (filename == null) {
			return "text";
		}
		String name = filename;
		int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
		if (slash >= 0) {
			name = name.substring(slash + 1);
		}
		// Special filenames without extension
		if ("dockerfile".equalsIgnoreCase(name) || "containerfile".equalsIgnoreCase(name)) {
			return "dockerfile";
		}
		int dot = name.lastIndexOf('.');
		if (dot < 0 || dot == name.length() - 1) {
			return "text";
		}
		String ext = name.substring(dot + 1).toLowerCase(Locale.US);
		String id = EXT_TO_LANG.get(ext);
		return id != null ? id : "text";
	}

	public static String displayName(String id) {
		if (id == null || "text".equals(id)) {
			return "Plain Text";
		}
		LangDef d = LANGS.get(id);
		return d != null ? d.displayName : "Plain Text";
	}

	// ---- Content sniffing: used for pasted/typed text with no file name ----
	private static final class Signature {
		final String langId;
		final Pattern pattern;
		final int weight;

		Signature(String langId, String regex, int weight) {
			this.langId = langId;
			this.pattern = Pattern.compile(regex, Pattern.MULTILINE);
			this.weight = weight;
		}
	}

	private static final Signature[] SIGNATURES = new Signature[]{
			// Very distinctive, high-confidence markers
			new Signature("shell", "^#!.*\\b(bash|sh|zsh|ksh)\\b", 6),
			new Signature("php", "<\\?php", 6),
			new Signature("html", "(?i)^\\s*<!DOCTYPE\\s+html", 6),
			new Signature("html", "(?i)<html[\\s>]|</html\\s*>", 5),
			new Signature("html", "(?i)</?(div|span|body|head|script|style|a|p|table|tr|td)[\\s>]", 1),
			new Signature("xml", "^\\s*<\\?xml\\s", 6),

			new Signature("json", "^\\s*[\\{\\[][\\s\\S]*[\\}\\]]\\s*$", 2),
			new Signature("json", "\"[^\"]+\"\\s*:\\s*(\"|[0-9tfn\\[\\{])", 1),

			new Signature("css", "[.#]?[a-zA-Z][\\w-]*\\s*\\{[^{}]*:[^{}]*;[^{}]*\\}", 3),

			new Signature("markdown", "^#{1,6}[ \\t]+\\S", 3),
			new Signature("markdown", "^```", 2),
			new Signature("markdown", "\\[[^\\]\\n]+\\]\\([^)\\n]+\\)", 1),

			new Signature("yaml", "^---\\s*$", 3),
			new Signature("yaml", "^[ \\t]*[\\w.-]+:\\s+\\S", 1),

			new Signature("sql", "(?i)\\bselect\\b[\\s\\S]{0,200}\\bfrom\\b", 4),
			new Signature("sql", "(?i)\\bcreate\\s+table\\b|\\binsert\\s+into\\b|\\bupdate\\b[\\s\\S]{0,80}\\bset\\b", 4),

			new Signature("java", "\\bpublic\\s+(final\\s+)?class\\s+\\w+", 4),
			new Signature("java", "\\bSystem\\.out\\.println\\b|\\bpublic\\s+static\\s+void\\s+main\\b", 4),
			new Signature("java", "^\\s*import\\s+java\\.", 3),

			new Signature("kotlin", "\\bfun\\s+main\\s*\\(|\\bfun\\s+\\w+\\s*\\(", 3),
			new Signature("kotlin", "\\bval\\s+\\w+\\s*[:=]|\\bprintln\\(", 1),

			new Signature("csharp", "\\busing\\s+System\\b|Console\\.WriteLine", 4),
			new Signature("csharp", "\\bnamespace\\s+\\w+", 2),

			new Signature("cpp", "#include\\s*<iostream>|std::\\w+|\\bcout\\s*<<", 4),
			new Signature("cpp", "\\btemplate\\s*<", 2),

			new Signature("c", "#include\\s*<\\w+\\.h>", 3),
			new Signature("c", "\\bint\\s+main\\s*\\(", 2),

			new Signature("go", "\\bpackage\\s+main\\b", 4),
			new Signature("go", "\\bfunc\\s+main\\s*\\(|:=", 2),

			new Signature("rust", "\\bfn\\s+main\\s*\\(|println!\\(", 4),
			new Signature("rust", "\\blet\\s+mut\\b", 2),

			new Signature("typescript", "\\binterface\\s+\\w+|:\\s*(string|number|boolean|any|void)\\b", 3),
			new Signature("typescript", "\\bimport\\s+type\\b|^\\s*export\\s+(default\\s+)?(class|function|const)", 2),

			new Signature("javascript", "\\bfunction\\s*\\w*\\s*\\(|=>", 2),
			new Signature("javascript", "\\bconsole\\.log\\(|\\bdocument\\.|\\bwindow\\.", 2),
			new Signature("javascript", "\\b(const|let|var)\\s+\\w+\\s*=", 1),

			new Signature("python", "\\bdef\\s+\\w+\\s*\\([^)]*\\)\\s*:", 4),
			new Signature("python", "^\\s*import\\s+\\w+\\s*$|^\\s*from\\s+\\w+\\s+import\\b", 3),
			new Signature("python", "\\bprint\\(|\\belif\\b|\\bself\\b", 1),

			new Signature("swift", "\\bfunc\\s+\\w+\\s*\\(|\\bvar\\s+\\w+\\s*:|\\blet\\s+\\w+\\s*:", 2),
			new Signature("swift", "\\bimport\\s+(Foundation|UIKit|SwiftUI)\\b", 3),
	};

	/**
	 * Best-effort language guess from raw content alone (no file name to go
	 * on - e.g. text pasted or typed straight into a new "Untitled" tab).
	 * Returns "text" when nothing scores confidently enough.
	 */
	public static String detectFromContent(String text) {
		if (text == null) {
			return "text";
		}
		String trimmed = text.trim();
		if (trimmed.length() < 6) {
			return "text";
		}
		// Sample the start of very large pastes to keep this cheap.
		String sample = trimmed.length() > 6000 ? trimmed.substring(0, 6000) : trimmed;

		Map<String, Integer> scores = new HashMap<String, Integer>();
		for (Signature sig : SIGNATURES) {
			Matcher m = sig.pattern.matcher(sample);
			int count = 0;
			while (m.find() && count < 20) {
				count++;
			}
			if (count > 0) {
				String key = sig.langId;
				Integer prev = scores.get(key);
				int add = sig.weight * Math.min(count, 3);
				scores.put(key, (prev == null ? 0 : prev) + add);
			}
		}

		String best = null;
		int bestScore = 0;
		for (Map.Entry<String, Integer> e : scores.entrySet()) {
			if (e.getValue() > bestScore) {
				bestScore = e.getValue();
				best = e.getKey();
			}
		}
		if (best == null || bestScore < 3) {
			return "text";
		}
		// "xml" is scored separately from "html" above but both render with
		// the same highlighter definition.
		if ("xml".equals(best)) {
			return "html";
		}
		return best;
	}


	/**
	 * Read-only viewers: returns a coloured copy of `text`, painted exactly like the text editor paints
	 * the same file, or null when the editor has no rules for this file name (or the file is too big).
	 */
	public static android.text.SpannableStringBuilder styled(CharSequence text, String fileName, boolean darkTheme) {
		String id = detectLanguage(fileName);
		if ("text".equals(id) || LANGS.get(id) == null) {
			return null;
		}
		if (text == null || text.length() == 0 || text.length() > MAX_HIGHLIGHT_LENGTH) {
			return null;
		}
		android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder(text);
		highlight(sb, id, darkTheme);
		return sb;
	}

	/** All selectable language ids, "text" (Plain Text) first. */
	public static String[] allLanguageIds() {
		String[] ids = new String[ORDER.size() + 1];
		ids[0] = "text";
		for (int i = 0; i < ORDER.size(); i++) {
			ids[i + 1] = ORDER.get(i);
		}
		return ids;
	}

	public static String[] allLanguageDisplayNames() {
		String[] ids = allLanguageIds();
		String[] names = new String[ids.length];
		for (int i = 0; i < ids.length; i++) {
			names[i] = displayName(ids[i]);
		}
		return names;
	}

	/**
	 * Re-highlights the given editable in place for the given language id.
	 * Safe to call repeatedly (e.g. after every debounced edit) - it first
	 * strips any spans it previously added, then re-applies fresh ones.
	 */
	/** Files up to this many chars are highlighted in full; bigger ones only near the viewport. */
	public static final int FULL_HIGHLIGHT_LIMIT = 12000;

	public static void highlight(Editable editable, String langId, boolean darkTheme) {
		highlightRange(editable, langId, darkTheme, 0, Integer.MAX_VALUE);
	}

	/**
	 * Highlights only tokens that intersect [from, to). The regex still scans from
	 * the start of the text (so multi-line comments/strings are tokenised correctly),
	 * but spans are only created for the requested window. Keeping the total span
	 * count small is what keeps scrolling and zooming fast on big files.
	 */
	public static void highlightRange(Editable editable, String langId, boolean darkTheme, int from, int to) {
		if (editable == null) {
			return;
		}
		clearHighlightSpans(editable);
		if (langId == null || "text".equals(langId)) {
			return;
		}
		LangDef def = LANGS.get(langId);
		if (def == null) {
			return;
		}
		int length = editable.length();
		if (length == 0 || length > MAX_HIGHLIGHT_LENGTH) {
			return;
		}
		String text = editable.toString();
		Matcher m = def.pattern.matcher(text);
		while (m.find()) {
			if (m.end() <= from) {
				continue;
			}
			if (m.start() >= to) {
				break;
			}
			for (int i = 0; i < def.groups.length; i++) {
				String g = def.groups[i];
				int s = m.start(g);
				if (s >= 0) {
					int e = m.end(g);
					applySpan(editable, g, s, e, darkTheme);
					break;
				}
			}
		}
	}

	private static void clearHighlightSpans(Editable editable) {
		// Typed lookup is far cheaper than scanning every span with Object.class.
		HlColor[] spans = editable.getSpans(0, editable.length(), HlColor.class);
		for (int i = 0; i < spans.length; i++) {
			editable.removeSpan(spans[i]);
		}
	}

	private static void applySpan(Editable editable, String group, int start, int end, boolean dark) {
		if (start < 0 || end <= start || end > editable.length()) {
			return;
		}
		int color = colorFor(group, dark);
		// Colour only. Bold/italic StyleSpans are MetricAffectingSpans: every one makes the
		// EditText's DynamicLayout re-measure and reflow (on add AND on remove), which
		// freezes the UI on files with thousands of keywords/comments.
		editable.setSpan(new HlColor(color), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
	}

	private static int colorFor(String group, boolean dark) {
		if (G_COMMENT.equals(group)) {
			return dark ? 0xFF6A9955 : 0xFF008000;
		}
		if (G_STRING.equals(group)) {
			return dark ? 0xFFCE9178 : 0xFFA31515;
		}
		if (G_NUMBER.equals(group)) {
			return dark ? 0xFFB5CEA1 : 0xFF098658;
		}
		if (G_KEYWORD.equals(group)) {
			return dark ? 0xFF569CD6 : 0xFF0000FF;
		}
		if (G_TYPE.equals(group)) {
			return dark ? 0xFF4EC9B0 : 0xFF267F99;
		}
		if (G_ANNOT.equals(group)) {
			return dark ? 0xFFC586C0 : 0xFFAF00DB;
		}
		if (G_TAG.equals(group)) {
			return dark ? 0xFF569CD6 : 0xFF800000;
		}
		if (G_ATTR.equals(group)) {
			return dark ? 0xFF9CDCFE : 0xFFFF0000;
		}
		return dark ? 0xFFD4D4D4 : 0xFF1A1A1A;
	}
}
