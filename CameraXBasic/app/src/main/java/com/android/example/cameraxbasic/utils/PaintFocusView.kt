package com.android.example.cameraxbasic.utils

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

class PaintFocusView : View, View.OnTouchListener {
    constructor(context: Context) : this(context, null)
    constructor(context: Context, attrs: AttributeSet?) : this(context, attrs, 0)
    constructor(context: Context, attrs: AttributeSet?, defStyle: Int) : super(
        context,
        attrs,
        defStyle
    )

    var paint: Paint? = null
    var xAxis: Float? = null
    var yAxis: Float? = null

    init {
        paint = Paint()
        xAxis = -100F
        yAxis = -100F
    }

    override fun onTouch(v: View?, event: MotionEvent?): Boolean {
        when (event?.action) {
            MotionEvent.ACTION_UP -> {
                xAxis = -100F
                yAxis = -100F
                Handler(Looper.getMainLooper()).postDelayed({ invalidate() }, 1000)
            }

            MotionEvent.ACTION_DOWN -> {
                xAxis = event.x
                yAxis = event.y
                invalidate()
            }
        }
        return true
    }

    override fun onDraw(canvas: Canvas?) {
        super.onDraw(canvas)

        paint?.style = Paint.Style.STROKE
        paint?.strokeWidth = 10F
        paint?.color = Color.LTGRAY
        canvas?.drawCircle(xAxis!!, yAxis!!, 100F, paint!!)
    }
}