package com.terafiles;

import android.graphics.Color;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import java.util.*;

/** Small hand-written tokenizer: colours comments, strings, numbers, keywords, tags. No regex, no deps. */
class SyntaxHighlighter {
	/** Above this size highlighting is skipped (each token costs a span object). */
	static final int MAX_CHARS = 400000;

	private static final String KW_JAVA = "abstract assert boolean break byte case catch char class const continue default do double else enum extends final finally float for goto if implements import instanceof int interface long native new package private protected public return short static strictfp super switch synchronized this throw throws transient try void volatile while true false null var record";
	private static final String KW_KOTLIN = "as break class continue do else false for fun if in interface is null object package return super this throw true try typealias val var when while by catch constructor finally get import init set where abstract annotation companion const data enum external final inline inner internal lateinit open operator out override private protected public sealed suspend vararg";
	private static final String KW_JS = "async await break case catch class const continue debugger default delete do else enum export extends false finally for function if implements import in instanceof interface let new null of package private protected public return static super switch this throw true try typeof undefined var void while with yield type declare namespace readonly abstract as from";
	private static final String KW_C = "auto break case char const continue default do double else enum extern float for goto if inline int long register return short signed sizeof static struct switch typedef union unsigned void volatile while class namespace template typename public private protected virtual new delete this true false nullptr bool using try catch throw operator";
	private static final String KW_PY = "and as assert async await break class continue def del elif else except False finally for from global if import in is lambda None nonlocal not or pass raise return True try while with yield self";
	private static final String KW_SH = "if then else elif fi for while do done case esac function in select until return exit export local echo cd read set unset shift source true false";
	private static final String KW_RUST = "as async await break const continue crate dyn else enum extern false fn for if impl in let loop match mod move mut pub ref return self Self static struct super trait true type unsafe use where while";
	private static final String KW_GO = "break case chan const continue default defer else fallthrough for func go goto if import interface map package range return select struct switch type var true false nil";
	private static final String KW_RUBY = "alias and begin break case class def do else elsif end ensure false for if in module next nil not or redo rescue retry return self super then true undef unless until when while yield";
	private static final String KW_PHP = "abstract and array as break case catch class clone const continue declare default do echo else elseif empty extends final finally for foreach function global if implements include instanceof interface isset namespace new null or print private protected public require return static switch this throw trait true false try use var while";
	private static final String KW_SQL = "select from where and or not insert into values update set delete create table drop alter add index join left right inner outer on group by order having limit offset as distinct null is in like between union all primary key foreign references default unique view case when then else end exists count sum avg min max asc desc";
	private static final String KW_CONF = "true false null yes no on off";

	private final int cKeyword, cString, cComment, cNumber, cType, cAnno, cTag, cAttr;
	private SpannableStringBuilder out;

	SyntaxHighlighter(boolean dark) {
		if (dark) {
			cKeyword = Color.rgb(204, 120, 50);
			cString = Color.rgb(106, 170, 89);
			cComment = Color.rgb(128, 128, 128);
			cNumber = Color.rgb(104, 151, 187);
			cType = Color.rgb(152, 118, 170);
			cAnno = Color.rgb(187, 181, 41);
			cTag = Color.rgb(232, 191, 106);
			cAttr = Color.rgb(160, 190, 230);
		} else {
			cKeyword = Color.rgb(0, 51, 179);
			cString = Color.rgb(6, 125, 23);
			cComment = Color.rgb(140, 140, 140);
			cNumber = Color.rgb(23, 80, 235);
			cType = Color.rgb(135, 16, 148);
			cAnno = Color.rgb(158, 136, 13);
			cTag = Color.rgb(0, 51, 179);
			cAttr = Color.rgb(0, 98, 122);
		}
	}

	private static Set<String> words(String list, boolean lower) {
		HashSet<String> set = new HashSet<String>();
		StringTokenizer st = new StringTokenizer(list, " ");
		while (st.hasMoreTokens()) {
			String w = st.nextToken();
			set.add(lower ? w.toLowerCase(Locale.US) : w);
		}
		return set;
	}

	/** @return a styled copy of text, or null if this extension/size isn't highlighted. */
	SpannableStringBuilder highlight(String text, String ext) {
		if (text.length() > MAX_CHARS || ext == null)
			return null;
		ext = ext.toLowerCase(Locale.US);
		out = new SpannableStringBuilder(text);
		if (ext.equals("xml") || ext.equals("html") || ext.equals("htm")) markup(text);
		else if (ext.equals("json")) json(text);
		else if (ext.equals("css")) css(text);
		else if (ext.equals("md") || ext.equals("markdown")) markdown(text);
		else if (ext.equals("java")) code(text, KW_JAVA, "//", true, "\"'", false, false, false, true, true, false);
		else if (ext.equals("kt") || ext.equals("kts")) code(text, KW_KOTLIN, "//", true, "\"'", false, false, false, true, true, false);
		else if (ext.equals("gradle")) code(text, KW_JAVA + " def", "//", true, "\"'", false, false, false, true, false, false);
		else if (ext.equals("js") || ext.equals("jsx") || ext.equals("ts") || ext.equals("tsx"))
			code(text, KW_JS, "//", true, "\"'`", false, false, false, true, true, false);
		else if (ext.equals("c") || ext.equals("cpp") || ext.equals("h") || ext.equals("hpp"))
			code(text, KW_C, "//", true, "\"'", false, true, false, true, false, false);
		else if (ext.equals("py")) code(text, KW_PY, "#", false, "\"'", false, false, false, true, true, true);
		else if (ext.equals("sh") || ext.equals("bash")) code(text, KW_SH, "#", false, "\"'", false, false, false, false, false, false);
		else if (ext.equals("rs")) code(text, KW_RUST, "//", true, "\"", false, false, false, true, false, false);
		else if (ext.equals("go")) code(text, KW_GO, "//", true, "\"'`", false, false, false, true, false, false);
		else if (ext.equals("rb")) code(text, KW_RUBY, "#", false, "\"'", false, false, false, true, false, false);
		else if (ext.equals("php")) code(text, KW_PHP, "//", true, "\"'", false, false, false, true, false, false);
		else if (ext.equals("sql")) code(text, KW_SQL, "--", true, "\"'", true, false, false, false, false, false);
		else if (ext.equals("yaml") || ext.equals("yml") || ext.equals("properties") || ext.equals("conf")
				|| ext.equals("cfg") || ext.equals("toml"))
			code(text, KW_CONF, "#", false, "\"'", true, false, true, false, false, false);
		else if (ext.equals("ini")) code(text, KW_CONF, ";", false, "\"'", true, false, true, false, false, false);
		else { out = null; return null; }
		SpannableStringBuilder r = out;
		out = null;
		return r;
	}

	private void span(int s, int e, int color) {
		if (e > out.length()) e = out.length();
		if (s < e) out.setSpan(new ForegroundColorSpan(color), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
	}

	private static int eol(String s, int i) {
		int e = s.indexOf('\n', i);
		return e < 0 ? s.length() : e;
	}

	/** Generic C-family / scripting / config scanner. */
	private void code(String s, String kwList, String lineComment, boolean blockComment, String quotes,
			boolean ci, boolean preproc, boolean keyValue, boolean types, boolean annos, boolean tripleQ) {
		Set<String> kw = words(kwList, ci);
		int n = s.length(), i = 0;
		boolean lineStart = true;
		while (i < n) {
			char c = s.charAt(i);
			if (c == '\n') { lineStart = true; i++; continue; }
			if (c == ' ' || c == '\t' || c == '\r') { i++; continue; }
			boolean first = lineStart;
			lineStart = false;

			if (first && keyValue) {
				if (c == '-' && i + 1 < n && s.charAt(i + 1) == ' ') { // yaml list item
					i += 2;
					lineStart = true; // allow "- key: value"
					continue;
				}
				if (c == '[') {
					int e = eol(s, i);
					int close = s.indexOf(']', i);
					if (close > 0 && close < e) { span(i, close + 1, cType); i = close + 1; continue; }
				}
				if (!s.startsWith(lineComment, i)) {
					int j = i;
					while (j < n && s.charAt(j) != ':' && s.charAt(j) != '=' && s.charAt(j) != '\n'
							&& s.charAt(j) != '"' && s.charAt(j) != '\'')
						j++;
					if (j < n && (s.charAt(j) == ':' || s.charAt(j) == '=') && j > i) {
						int ke = j;
						while (ke > i && s.charAt(ke - 1) == ' ') ke--;
						span(i, ke, cAttr);
						i = j + 1;
						continue;
					}
				}
			}
			if (preproc && first && c == '#') {
				int e = eol(s, i);
				span(i, e, cAnno);
				i = e;
				continue;
			}
			if (lineComment != null && s.startsWith(lineComment, i)) {
				int e = eol(s, i);
				span(i, e, cComment);
				i = e;
				continue;
			}
			if (blockComment && c == '/' && i + 1 < n && s.charAt(i + 1) == '*') {
				int e = s.indexOf("*/", i + 2);
				e = e < 0 ? n : e + 2;
				span(i, e, cComment);
				i = e;
				continue;
			}
			if (tripleQ && (s.startsWith("\"\"\"", i) || s.startsWith("'''", i))) {
				String q = s.substring(i, i + 3);
				int e = s.indexOf(q, i + 3);
				e = e < 0 ? n : e + 3;
				span(i, e, cString);
				i = e;
				continue;
			}
			if (quotes.indexOf(c) >= 0) {
				int j = i + 1;
				while (j < n) {
					char ch = s.charAt(j);
					if (ch == '\\') { j += 2; continue; }
					if (ch == c) { j++; break; }
					if (ch == '\n' && c != '`') break;
					j++;
				}
				if (j > n) j = n;
				span(i, j, cString);
				i = j;
				continue;
			}
			if (c >= '0' && c <= '9') {
				int j = i + 1;
				while (j < n && (Character.isLetterOrDigit(s.charAt(j)) || s.charAt(j) == '.' || s.charAt(j) == '_')) j++;
				span(i, j, cNumber);
				i = j;
				continue;
			}
			if (annos && c == '@' && i + 1 < n && Character.isJavaIdentifierStart(s.charAt(i + 1))) {
				int j = i + 1;
				while (j < n && Character.isJavaIdentifierPart(s.charAt(j))) j++;
				span(i, j, cAnno);
				i = j;
				continue;
			}
			if (Character.isJavaIdentifierStart(c)) {
				int j = i + 1;
				while (j < n && Character.isJavaIdentifierPart(s.charAt(j))) j++;
				String w = s.substring(i, j);
				if (kw.contains(ci ? w.toLowerCase(Locale.US) : w)) span(i, j, cKeyword);
				else if (types && w.length() > 1 && Character.isUpperCase(w.charAt(0)) && !isAllUpper(w)) span(i, j, cType);
				i = j;
				continue;
			}
			i++;
		}
	}

	private static boolean isAllUpper(String w) {
		for (int i = 0; i < w.length(); i++) {
			char c = w.charAt(i);
			if (Character.isLowerCase(c)) return false;
		}
		return true;
	}

	private static boolean nameChar(char c) {
		return Character.isLetterOrDigit(c) || c == ':' || c == '-' || c == '_' || c == '.';
	}

	private void markup(String s) {
		int n = s.length(), i = 0;
		while (i < n) {
			int lt = s.indexOf('<', i);
			if (lt < 0) break;
			if (s.startsWith("<!--", lt)) {
				int e = s.indexOf("-->", lt + 4);
				e = e < 0 ? n : e + 3;
				span(lt, e, cComment);
				i = e;
				continue;
			}
			if (s.startsWith("<![CDATA[", lt)) {
				int e = s.indexOf("]]>", lt);
				e = e < 0 ? n : e + 3;
				span(lt, e, cString);
				i = e;
				continue;
			}
			int j = lt + 1;
			if (j >= n) break;
			char f = s.charAt(j);
			if (f == '/' || f == '?' || f == '!') j++;
			else if (!Character.isLetter(f) && f != '_') { i = lt + 1; continue; } // stray '<'
			while (j < n && nameChar(s.charAt(j))) j++;
			span(lt, j, cTag);
			while (j < n) {
				char c = s.charAt(j);
				if (c == '>') { span(j, j + 1, cTag); j++; break; }
				if ((c == '/' || c == '?') && j + 1 < n && s.charAt(j + 1) == '>') { span(j, j + 2, cTag); j += 2; break; }
				if (c == '"' || c == '\'') {
					int k = s.indexOf(c, j + 1);
					int e = k < 0 ? n : k + 1;
					span(j, e, cString);
					j = e;
					continue;
				}
				if (Character.isLetter(c) || c == '_' || c == ':') {
					int k = j;
					while (k < n && nameChar(s.charAt(k))) k++;
					span(j, k, cAttr);
					j = k;
					continue;
				}
				j++;
			}
			i = j;
		}
	}

	private void json(String s) {
		int n = s.length(), i = 0;
		while (i < n) {
			char c = s.charAt(i);
			if (c == '"') {
				int j = i + 1;
				while (j < n) {
					char ch = s.charAt(j);
					if (ch == '\\') { j += 2; continue; }
					if (ch == '"') { j++; break; }
					if (ch == '\n') break;
					j++;
				}
				if (j > n) j = n;
				int k = j;
				while (k < n && (s.charAt(k) == ' ' || s.charAt(k) == '\t')) k++;
				span(i, j, (k < n && s.charAt(k) == ':') ? cAttr : cString);
				i = j;
			} else if ((c >= '0' && c <= '9') || (c == '-' && i + 1 < n && Character.isDigit(s.charAt(i + 1)))) {
				int j = i + 1;
				while (j < n && "0123456789.eE+-".indexOf(s.charAt(j)) >= 0) j++;
				span(i, j, cNumber);
				i = j;
			} else if (Character.isLetter(c)) {
				int j = i + 1;
				while (j < n && Character.isLetter(s.charAt(j))) j++;
				String w = s.substring(i, j);
				if (w.equals("true") || w.equals("false") || w.equals("null")) span(i, j, cKeyword);
				i = j;
			} else i++;
		}
	}

	private void css(String s) {
		int n = s.length(), i = 0, depth = 0;
		while (i < n) {
			char c = s.charAt(i);
			if (c == '/' && i + 1 < n && s.charAt(i + 1) == '*') {
				int e = s.indexOf("*/", i + 2);
				e = e < 0 ? n : e + 2;
				span(i, e, cComment);
				i = e;
			} else if (c == '"' || c == '\'') {
				int j = i + 1;
				while (j < n && s.charAt(j) != c && s.charAt(j) != '\n') { if (s.charAt(j) == '\\') j++; j++; }
				j = Math.min(n, j + 1);
				span(i, j, cString);
				i = j;
			} else if (c == '{') { depth++; i++; }
			else if (c == '}') { if (depth > 0) depth--; i++; }
			else if (c == '@') {
				int j = i + 1;
				while (j < n && (Character.isLetter(s.charAt(j)) || s.charAt(j) == '-')) j++;
				span(i, j, cKeyword);
				i = j;
			} else if (depth > 0 && c == '#') {
				int j = i + 1;
				while (j < n && Character.digit(s.charAt(j), 16) >= 0) j++;
				span(i, j, cNumber);
				i = Math.max(j, i + 1);
			} else if (depth > 0 && Character.isDigit(c)) {
				int j = i + 1;
				while (j < n && (Character.isLetterOrDigit(s.charAt(j)) || s.charAt(j) == '.' || s.charAt(j) == '%')) j++;
				span(i, j, cNumber);
				i = j;
			} else if (depth == 0 && (c == '.' || c == '#') && i + 1 < n && Character.isLetter(s.charAt(i + 1))) {
				int j = i + 1;
				while (j < n && (Character.isLetterOrDigit(s.charAt(j)) || s.charAt(j) == '-' || s.charAt(j) == '_')) j++;
				span(i, j, cType);
				i = j;
			} else if (Character.isLetter(c) || c == '-' || c == '_') {
				int j = i + 1;
				while (j < n && (Character.isLetterOrDigit(s.charAt(j)) || s.charAt(j) == '-' || s.charAt(j) == '_')) j++;
				int k = j;
				while (k < n && s.charAt(k) == ' ') k++;
				if (depth > 0 && k < n && s.charAt(k) == ':') span(i, j, cAttr);
				else if (depth == 0) span(i, j, cTag);
				i = j;
			} else i++;
		}
	}

	private void markdown(String s) {
		int n = s.length(), i = 0;
		boolean fence = false;
		while (i < n) {
			int e = eol(s, i);
			if (s.startsWith("```", i)) {
				fence = !fence;
				span(i, e, cString);
			} else if (fence) {
				span(i, e, cString);
			} else if (s.charAt(i) == '#') {
				span(i, e, cKeyword);
				out.setSpan(new StyleSpan(android.graphics.Typeface.BOLD), i, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
			} else if (s.charAt(i) == '>') {
				span(i, e, cComment);
			} else {
				int p = i;
				while (p < e) {
					int a = s.indexOf('`', p);
					if (a < 0 || a >= e) break;
					int b = s.indexOf('`', a + 1);
					if (b < 0 || b >= e) break;
					span(a, b + 1, cString);
					p = b + 1;
				}
			}
			i = e + 1;
		}
	}
}
