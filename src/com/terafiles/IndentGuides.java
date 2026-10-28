package com.terafiles;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.Layout;
import android.widget.EditText;

import java.util.ArrayList;

/**
 * Draws indent guide lines behind the text of an EditText (only for the rows on screen),
 * highlights the block around the cursor in green, and for HTML/XML highlights the
 * tag pair around the cursor.
 */
public class IndentGuides {

    private static final int MAX_TAG_SCAN = 300000;
    private static final String[] VOID_TAGS = {"area", "base", "br", "col", "embed", "hr", "img", "input",
		"link", "meta", "param", "source", "track", "wbr"};

    private boolean enabled = true;
    private boolean tagMode = false;
    private boolean dark = true;
    private int defaultIndent = 4;

    private final Paint line = new Paint();
    private final Paint active = new Paint();
    private final Paint fill = new Paint();

    // indent size detection (per text version)
    private int sizeVer = -1;
    private int indentSize = 4;

    // block around the cursor (indent mode)
    private int blkVer = -1;
    private int blkLine = -1;
    private int blkLevel = -1;
    private int blkStart = 0;
    private int blkEnd = 0;

    // tag pairs (HTML/XML)
    private int tagVer = -1;
    private final ArrayList tagPairs = new ArrayList(); // of int[8]
    private int pairVer = -1;
    private int pairPos = -1;
    private int[] curPair = null;

    /** Returns true if anything changed (caller should repaint). */
    public boolean configure(boolean enabled, boolean tagMode, boolean dark, int defaultIndent) {
        boolean changed = this.enabled != enabled || this.tagMode != tagMode || this.dark != dark
			|| this.defaultIndent != defaultIndent;
        this.enabled = enabled;
        this.tagMode = tagMode;
        this.dark = dark;
        this.defaultIndent = defaultIndent;
        if (changed) {
            sizeVer = -1;
            blkVer = -1;
            pairVer = -1;
        }
        return changed;
    }

    // ------------------------------------------------------------------ text helpers

    private static int lineStart(CharSequence t, int pos) {
        while (pos > 0 && t.charAt(pos - 1) != '\n') pos--;
        return pos;
    }

    private static int lineEnd(CharSequence t, int pos) {
        int len = t.length();
        while (pos < len && t.charAt(pos) != '\n') pos++;
        return pos;
    }

    /**
     * Indent level of a line, or -1 if the line is blank. A flush-left fragment (typically the
     * tail of a word split by an accidental Enter) is treated like a blank line, so it inherits
     * the surrounding level instead of cutting the block in two. A flush-left line counts as a
     * fragment when the previous code line is indented and does not end a statement
     * (";", "{" or "}"), or when it sits between two equally indented lines.
     */
    private int lineDepth(CharSequence t, int ls, int le) {
        int d = rawDepth(t, ls, le);
        if (d != 0 || ls >= le) return d;
        if (!fragmentStart(t, ls)) return d;

        int len = t.length();
        int prev = -1;
        int prevStart = -1;   // the nearest non-blank line above (may itself be a fragment)
        int p = ls;
        for (int n = 0; n < 60 && p > 0; n++) {
            int ps = lineStart(t, p - 1);
            int pd = rawDepth(t, ps, lineEnd(t, ps));
            if (pd >= 0) {
                if (prevStart < 0) prevStart = ps;
                // a word split more than once leaves several flush-left fragments in a row:
                // look through them to the real indented line above
                if (pd == 0 && fragmentStart(t, ps)) {
                    p = ps;
                    continue;
                }
                prev = pd;
                break;
            }
            p = ps;
        }
        if (prev < 1) return d;

        // last visible character of the nearest line above
        int pe = lineEnd(t, prevStart);
        while (pe > prevStart && (t.charAt(pe - 1) == '\r' || t.charAt(pe - 1) == ' '
			   || t.charAt(pe - 1) == '\t')) pe--;
        char last = pe > prevStart ? t.charAt(pe - 1) : ';';
        if (last != ';' && last != '{' && last != '}') return -1;

        // previous line ended a statement: only a fragment if it sits between equal levels
        int next = -1;
        int q = le;
        for (int n = 0; n < 30 && q < len; n++) {
            int ns = q + 1;
            int ne = lineEnd(t, ns);
            int nd = rawDepth(t, ns, ne);
            if (nd >= 0) {
                next = nd;
                break;
            }
            q = ne;
        }
        int e = le;
        while (e > ls && (t.charAt(e - 1) == '\r' || t.charAt(e - 1) == ' ')) e--;
        boolean opener = e > ls && (t.charAt(e - 1) == '{' || t.charAt(e - 1) == ':');
        return (next == prev && !opener) ? -1 : d;
    }

    /** True if the line starts flush-left with something that could be the tail of a split word. */
    private static boolean fragmentStart(CharSequence t, int ls) {
        if (ls >= t.length()) return false;
        char c = t.charAt(ls);
        return !(c == '}' || c == '*' || c == '@' || c == '#' || c == '/' || c == '<'
			|| c == '\r' || c == '\t' || c == ' ' || c == '\n');
    }

    private int rawDepth(CharSequence t, int ls, int le) {
        int cols = 0;
        boolean tab = false;
        for (int i = ls; i < le; i++) {
            char c = t.charAt(i);
            if (c == ' ') cols++;
            else if (c == '\t') {
                cols += indentSize;
                tab = true;
            } else if (c == '\r') continue;
            else {
                int d = cols / indentSize;
                // A few stray spaces (and no tab) in front of real code, e.g. one accidental space
                // on a tab-indented file: count it as one level. Otherwise that single line reads
                // as level 0 and collapses the whole block structure around it. "*" lines
                // (Javadoc " * text") keep their natural level.
                if (d == 0 && cols > 0 && !tab && c != '*') d = 1;
                return d;
            }
        }
        return -1;
    }

    /** Blank lines inherit the smaller of the nearest non-blank levels above and below. */
    private int blankDepth(CharSequence t, int ls, int le) {
        int len = t.length();
        int prev = 0;
        int p = ls;
        for (int n = 0; n < 30 && p > 0; n++) {
            int ps = lineStart(t, p - 1);
            int d = lineDepth(t, ps, lineEnd(t, ps));
            if (d >= 0) {
                prev = d;
                break;
            }
            p = ps;
        }
        int next = 0;
        int q = le;
        for (int n = 0; n < 30 && q < len; n++) {
            int ns = q + 1;
            int ne = lineEnd(t, ns);
            int d = lineDepth(t, ns, ne);
            if (d >= 0) {
                next = d;
                break;
            }
            q = ne;
        }
        return Math.min(prev, next);
    }

    /** Start of the nearest non-blank line above (else below) a blank line, or -1. */
    private int referenceLine(CharSequence t, int ls, int le) {
        int len = t.length();
        int p = ls;
        for (int n = 0; n < 30 && p > 0; n++) {
            int ps = lineStart(t, p - 1);
            if (lineDepth(t, ps, lineEnd(t, ps)) >= 0) return ps;
            p = ps;
        }
        int q = le;
        for (int n = 0; n < 30 && q < len; n++) {
            int ns = q + 1;
            int ne = lineEnd(t, ns);
            if (lineDepth(t, ns, ne) >= 0) return ns;
            q = ne;
        }
        return -1;
    }

    private void detectIndentSize(CharSequence t, int ver) {
        if (sizeVer == ver) return;
        sizeVer = ver;
        int len = Math.min(t.length(), 30000);
        int best = 0;
        int seen = 0;
        int i = 0;
        while (i < len && seen < 400) {
            int e = lineEnd(t, i);
            int cols = 0;
            boolean tab = false;
            boolean nonBlank = false;
            for (int k = i; k < e; k++) {
                char c = t.charAt(k);
                if (c == ' ') cols++;
                else if (c == '\t') {
                    tab = true;
                    break;
                } else if (c == '\r') continue;
                else {
                    nonBlank = true;
                    break;
                }
            }
            if (nonBlank && !tab) {
                seen++;
                if (cols >= 2 && (best == 0 || cols < best)) best = cols;
            }
            i = e + 1;
        }
        if (best < 2 || best > 8) best = defaultIndent;
        if (best < 2) best = 2;
        indentSize = best;
    }

    // ------------------------------------------------------------------ block around the cursor

    private void computeBlock(CharSequence t, int pos, int ver) {
        int len = t.length();
        if (pos < 0) pos = 0;
        if (pos > len) pos = len;
        int cl = lineStart(t, pos);
        if (blkVer == ver && blkLine == cl) return;
        blkVer = ver;
        blkLine = cl;
        blkLevel = -1;

        int ce = lineEnd(t, cl);
        // The cursor sits on a flush-left fragment of a split word: it belongs to the code line
        // above, so work out the block from that line (and the fragment stays inside it).
        if (ce > cl && lineDepth(t, cl, ce) < 0 && rawDepth(t, cl, ce) == 0
			&& fragmentStart(t, cl)) {
            int pp = cl;
            for (int n = 0; n < 60 && pp > 0; n++) {
                int ps = lineStart(t, pp - 1);
                int pe = lineEnd(t, ps);
                if (lineDepth(t, ps, pe) >= 0) {
                    cl = ps;
                    ce = pe;
                    break;
                }
                pp = ps;
            }
        }
        int d = lineDepth(t, cl, ce);
        boolean blank = d < 0;
        if (blank) d = blankDepth(t, cl, ce);

        int nextDepth = -1;
        if (!blank) {
            int q = ce;
            for (int n = 0; n < 40 && q < len; n++) {
                int ns = q + 1;
                int ne = lineEnd(t, ns);
                int nd = lineDepth(t, ns, ne);
                if (nd >= 0) {
                    nextDepth = nd;
                    break;
                }
                q = ne;
            }
        }

        int prevDepth = -1;
        if (!blank) {
            int p0 = cl;
            for (int n = 0; n < 40 && p0 > 0; n++) {
                int ps = lineStart(t, p0 - 1);
                int pd = lineDepth(t, ps, lineEnd(t, ps));
                if (pd >= 0) {
                    prevDepth = pd;
                    break;
                }
                p0 = ps;
            }
        }

        int level;
        int need;
        boolean opens = false;
        boolean closes = false;
        if (!blank && nextDepth > d) {
            level = d;          // cursor is on a header line: highlight the block it opens
            need = d + 1;
            opens = true;
        } else if (!blank && prevDepth > d) {
            level = d;          // cursor is on a closing line (e.g. "}"): highlight the block it closes
            need = d + 1;
            closes = true;
        } else if (d >= 1) {
            level = d - 1;      // cursor is inside a block
            need = d;
        } else {
            return;
        }

        int bs;
        if (opens) {
            bs = Math.min(len, ce + 1);
        } else {
            bs = cl;
            int guard = 0;
            while (bs > 0 && guard < 3000) {
                int ps = lineStart(t, bs - 1);
                int pd = lineDepth(t, ps, lineEnd(t, ps));
                if (pd >= 0 && pd < need) break;
                bs = ps;
                guard++;
            }
        }
        int be = len;
        if (closes) {
            be = cl;            // the block ends just above the closing line
        } else {
            int p = ce;
            int guard = 0;
            while (p < len && guard < 3000) {
                int ns = p + 1;
                int ne = lineEnd(t, ns);
                int nd = lineDepth(t, ns, ne);
                if (nd >= 0 && nd < need) {
                    be = ns;
                    break;
                }
                p = ne;
                guard++;
            }
        }
        blkLevel = level;
        blkStart = bs;
        blkEnd = be;
    }

    // ------------------------------------------------------------------ HTML / XML tag pairs

    private static boolean matches(CharSequence t, int i, String s) {
        int n = s.length();
        if (i + n > t.length()) return false;
        for (int k = 0; k < n; k++) {
            if (t.charAt(i + k) != s.charAt(k)) return false;
        }
        return true;
    }

    private static int indexOf(CharSequence t, String s, int from, int limit) {
        int n = s.length();
        for (int i = from; i + n <= limit; i++) {
            if (matches(t, i, s)) return i;
        }
        return -1;
    }

    private static int indexOfIgnoreCase(CharSequence t, String s, int from, int limit) {
        int n = s.length();
        for (int i = from; i + n <= limit; i++) {
            boolean ok = true;
            for (int k = 0; k < n; k++) {
                if (Character.toLowerCase(t.charAt(i + k)) != s.charAt(k)) {
                    ok = false;
                    break;
                }
            }
            if (ok) return i;
        }
        return -1;
    }

    private static boolean isNameChar(char c) {
        return Character.isLetterOrDigit(c) || c == ':' || c == '-' || c == '_' || c == '.';
    }

    private static boolean isVoid(String name) {
        for (int i = 0; i < VOID_TAGS.length; i++) {
            if (VOID_TAGS[i].equals(name)) return true;
        }
        return false;
    }

    private void parseTags(CharSequence t) {
        tagPairs.clear();
        int n = Math.min(t.length(), MAX_TAG_SCAN);
        ArrayList stack = new ArrayList(); // of int[4]
        ArrayList names = new ArrayList(); // of String
        int i = 0;
        while (i < n) {
            if (t.charAt(i) != '<') {
                i++;
                continue;
            }
            if (matches(t, i, "<!--")) {
                int e = indexOf(t, "--" + ">", i + 4, n);
                i = e < 0 ? n : e + 3;
                continue;
            }
            if (matches(t, i, "<![CDATA[")) {
                int e = indexOf(t, "]]>", i + 9, n);
                i = e < 0 ? n : e + 3;
                continue;
            }
            if (i + 1 >= n) break;
            char c1 = t.charAt(i + 1);
            if (c1 == '!' || c1 == '?') {
                int e = indexOf(t, ">", i + 2, n);
                i = e < 0 ? n : e + 1;
                continue;
            }
            boolean closing = (c1 == '/');
            int ns = i + (closing ? 2 : 1);
            if (ns >= n || !Character.isLetter(t.charAt(ns))) {
                i++;
                continue;
            }
            int j = ns;
            while (j < n && isNameChar(t.charAt(j))) j++;
            String name = t.subSequence(ns, j).toString().toLowerCase();
            // find the closing '>' while ignoring '>' inside quoted attribute values
            int k = j;
            char quote = 0;
            while (k < n) {
                char ch = t.charAt(k);
                if (quote != 0) {
                    if (ch == quote) quote = 0;
                } else if (ch == '"' || ch == '\'') {
                    quote = ch;
                } else if (ch == '>') {
                    break;
                }
                k++;
            }
            if (k >= n) break;
            int tagEnd = k + 1;
            if (!closing) {
                boolean selfClose = t.charAt(k - 1) == '/';
                if (!selfClose && !isVoid(name)) {
                    stack.add(new int[]{i, tagEnd, ns, j});
                    names.add(name);
                    if (name.equals("script") || name.equals("style")) {
                        int e = indexOfIgnoreCase(t, "</" + name, tagEnd, n);
                        i = e < 0 ? n : e;
                        continue;
                    }
                }
                i = tagEnd;
            } else {
                int idx = -1;
                for (int s = names.size() - 1; s >= 0; s--) {
                    if (names.get(s).equals(name)) {
                        idx = s;
                        break;
                    }
                }
                if (idx >= 0) {
                    int[] o = (int[]) stack.get(idx);
                    tagPairs.add(new int[]{o[0], o[1], o[2], o[3], i, tagEnd, ns, j});
                    // anything left open above it (e.g. <p>, <li>) is dropped
                    while (stack.size() > idx) {
                        stack.remove(stack.size() - 1);
                        names.remove(names.size() - 1);
                    }
                }
                i = tagEnd;
            }
        }
    }

    private int[] findPair(CharSequence t, int pos, int ver) {
        if (tagVer != ver) {
            tagVer = ver;
            parseTags(t);
            pairVer = -1;
        }
        if (pairVer == ver && pairPos == pos) return curPair;
        pairVer = ver;
        pairPos = pos;
        int[] best = null;
        for (int i = 0; i < tagPairs.size(); i++) {
            int[] p = (int[]) tagPairs.get(i);
            if (p[0] <= pos && pos <= p[5]) {
                if (best == null || p[0] > best[0]) best = p;
            }
        }
        curPair = best;
        return best;
    }

    // ------------------------------------------------------------------ drawing

    /** Draws into the editor's (scrolled) canvas. Call BEFORE the text is drawn. */
    public void draw(Canvas canvas, EditText ed, int ver, float density) {
        if (!enabled) return;
        Layout l = ed.getLayout();
        if (l == null) return;
        CharSequence t = ed.getText();
        int len = t.length();
        if (len == 0) return;

        detectIndentSize(t, ver);

        int scrollY = ed.getScrollY();
        int padTop = ed.getExtendedPaddingTop();
        int padLeft = ed.getCompoundPaddingLeft();
        int top0 = Math.max(0, scrollY - padTop);
        int first = l.getLineForVertical(top0);
        int last = Math.min(l.getLineCount() - 1, l.getLineForVertical(top0 + ed.getHeight()));

        float stroke = Math.max(1f, density);
        line.setStrokeWidth(stroke);
        line.setColor(dark ? 0x33FFFFFF : 0x33000000);
        active.setStrokeWidth(stroke * 1.6f);
        active.setColor(dark ? 0xCC4CAF50 : 0xCC2E7D32);

        int pos = ed.getSelectionStart();
        if (!tagMode) {
            computeBlock(t, pos, ver);
        }

        float cw = ed.getPaint().measureText("0");
        float nudge = 1.5f * density;

        int curLs = -1;
        int depth = 0;
        int refLs = 0;          // line whose characters define the x positions of the guides
        boolean tabIndent = false;
        for (int v = first; v <= last; v++) {
            int s = l.getLineStart(v);
            int ls = (s <= 0 || s > len || t.charAt(s - 1) == '\n') ? Math.min(Math.max(s, 0), len)
				: lineStart(t, s);
            if (ls != curLs) {
                curLs = ls;
                int le = lineEnd(t, ls);
                int d = lineDepth(t, ls, le);
                refLs = ls;
                if (d < 0) {
                    // blank line: it has no (or too few) indent characters of its own, so take
                    // the tab/space style and x positions from the nearest non-blank line
                    d = blankDepth(t, ls, le);
                    refLs = referenceLine(t, ls, le);
                }
                depth = d;
                tabIndent = refLs >= 0 && refLs < len && t.charAt(refLs) == '\t';
            }
            if (depth <= 0) continue;
            float top = padTop + l.getLineTop(v);
            float bottom = padTop + l.getLineTop(v + 1);
            for (int k = 0; k < depth; k++) {
                float x = tabIndent
					? padLeft + l.getPrimaryHorizontal(Math.min(len, refLs + k))
					: padLeft + k * indentSize * cw;
                x -= nudge;
                boolean act = !tagMode && blkLevel == k && ls >= blkStart && ls < blkEnd;
                canvas.drawLine(x, top, x, bottom, act ? active : line);
            }
        }

        if (tagMode && len <= MAX_TAG_SCAN && pos >= 0) {
            int[] p = findPair(t, pos, ver);
            if (p != null) {
                int openRow = l.getLineForOffset(Math.max(0, p[1] - 1));
                int closeRow = l.getLineForOffset(Math.min(len, p[4]));
                if (closeRow > openRow) {
                    float x = padLeft + l.getPrimaryHorizontal(Math.min(len, p[0])) - nudge;
                    canvas.drawLine(x, padTop + l.getLineBottom(openRow), x, padTop + l.getLineTop(closeRow),
									active);
                }
                fill.setColor(dark ? 0x334CAF50 : 0x332E7D32);
                tagBox(canvas, l, p[0], p[3], padLeft, padTop, len);
                tagBox(canvas, l, p[4], p[7], padLeft, padTop, len);
            }
        }
    }

    private void tagBox(Canvas canvas, Layout l, int from, int to, int padLeft, int padTop, int len) {
        if (from < 0 || to > len || to <= from) return;
        int r = l.getLineForOffset(from);
        if (l.getLineForOffset(to - 1) != r) {
            return; // tag name wraps onto another row: skip
        }
        float x1 = padLeft + l.getPrimaryHorizontal(from);
        float x2 = padLeft + l.getPrimaryHorizontal(to);
        if (x2 <= x1) return;
        canvas.drawRect(x1, padTop + l.getLineTop(r), x2, padTop + l.getLineBottom(r), fill);
    }
}

