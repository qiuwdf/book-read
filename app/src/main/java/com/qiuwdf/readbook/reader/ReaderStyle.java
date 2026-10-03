package com.qiuwdf.readbook.reader;

import android.content.Context;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.TextPaint;

import com.qiuwdf.readbook.util.Ui;

/**
 * 阅读排版样式（字号 / 行距 / 字体 / 颜色 / 边距）。
 */
public class ReaderStyle {

    public int bgColor;
    public int textColor;
    public int hintColor;

    public float textSizePx;
    public float lineHeightPx;
    public boolean indent = true;

    public int padH;
    public int padV;

    public TextPaint textPaint;
    public TextPaint titlePaint;
    public TextPaint footerPaint;

    public float titleHeightPx;

    public static ReaderStyle fromPrefs(Context c, int bgIndex, boolean night) {
        ReaderStyle s = new ReaderStyle();
        s.bgColor = ReadThemes.bg(bgIndex, night);
        s.textColor = ReadThemes.text(bgIndex, night);
        s.hintColor = ReadThemes.hint(bgIndex, night);

        float sizeSp = com.qiuwdf.readbook.core.Prefs.get().fontSize();
        s.textSizePx = Ui.sp(c, sizeSp);

        Typeface face = com.qiuwdf.readbook.core.Prefs.get().fontSerif()
                ? Typeface.SERIF : Typeface.SANS_SERIF;
        s.textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        s.textPaint.setTypeface(face);
        s.textPaint.setTextSize(s.textSizePx);
        s.textPaint.setColor(s.textColor);

        s.titlePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        s.titlePaint.setTypeface(face);
        s.titlePaint.setTextSize(s.textSizePx * 1.15f);
        s.titlePaint.setFakeBoldText(true);
        s.titlePaint.setColor(s.textColor);

        s.footerPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        s.footerPaint.setTypeface(face);
        s.footerPaint.setTextSize(s.textSizePx * 0.62f);
        s.footerPaint.setColor(s.hintColor);

        Paint.FontMetricsInt fm = s.textPaint.getFontMetricsInt();
        int base = fm.descent - fm.ascent;
        float spacingPercent = com.qiuwdf.readbook.core.Prefs.get().lineSpacingPercent();
        s.lineHeightPx = base * (spacingPercent / 100f);

        s.titleHeightPx = (s.titlePaint.getFontMetricsInt().descent
                - s.titlePaint.getFontMetricsInt().ascent) * 2.2f;

        s.indent = com.qiuwdf.readbook.core.Prefs.get().indent();
        s.padH = Ui.dpInt(c, 20);
        s.padV = Ui.dpInt(c, 14);
        return s;
    }
}
