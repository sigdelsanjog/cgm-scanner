package de.welthungerhilfe.cgm.scanner.ui.views;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Paint;
import androidx.appcompat.widget.AppCompatEditText;
import android.text.TextPaint;
import android.text.method.DigitsKeyListener;
import android.util.AttributeSet;

import java.util.Locale;

import de.welthungerhilfe.cgm.scanner.R;

public class UnitEditText extends AppCompatEditText {
    TextPaint textPaint = new TextPaint();
    private String unit = "";
    private float unitPadding;
    private float unitMargin;

    public UnitEditText(Context context) {
        super(context);
        setLocale();
    }

    public UnitEditText(Context context, AttributeSet attrs) {
        super(context, attrs);
        getAttributes(context, attrs, 0);
        setLocale();
    }

    public UnitEditText(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        getAttributes(context, attrs, defStyleAttr);
        setLocale();
    }

    @Override
    public void onDraw(Canvas c){
        super.onDraw(c);

        if (!getText().toString().isEmpty()) {
            int suffixXPosition = (int) (textPaint.measureText(getText().toString()) + unitMargin) + getPaddingLeft();
            c.drawText(unit, Math.max(suffixXPosition, unitPadding), getBaseline(), textPaint);
        }
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        textPaint.setColor(getCurrentTextColor());
        textPaint.setTextSize(getTextSize());
        textPaint.setTextAlign(Paint.Align.LEFT);
    }

    private void getAttributes(Context context, AttributeSet attrs, int defStyleAttr) {
        TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.UnitEditText, defStyleAttr, 0);
        if(a != null) {
            unit = a.getString(R.styleable.UnitEditText_unit);
            if(unit == null) {
                unit = "";
            }
            unitPadding = a.getDimension(R.styleable.UnitEditText_unitPadding, 0);
            unitMargin = a.getDimension(R.styleable.UnitEditText_unitMargin, 0);
        }
        a.recycle();
    }

    private void setLocale() {
        setKeyListener(DigitsKeyListener.getInstance("0123456789,."));
    }
}