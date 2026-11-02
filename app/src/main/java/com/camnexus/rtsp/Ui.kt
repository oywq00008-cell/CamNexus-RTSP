package com.camnexus.rtsp

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/** 轻量的对话框与提示，避免引入 Material 库。 */
object Ui {

    fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    fun toast(context: Context, text: String) {
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    }

    /** 底部弹出的单选列表。 */
    fun picker(
        context: Context,
        title: String,
        options: List<String>,
        selected: Int,
        onPick: (Int) -> Unit
    ) {
        val dialog = Dialog(context)
        dialog.setContentView(R.layout.dialog_picker)
        val window = dialog.window ?: return
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
        window.setGravity(Gravity.BOTTOM)
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.setDimAmount(0.6f)

        dialog.findViewById<TextView>(R.id.dlgTitle)?.text = title
        val box = dialog.findViewById<LinearLayout>(R.id.dlgOptions)
        if (box != null) {
            options.forEachIndexed { index, label ->
                val row = LayoutInflater.from(context).inflate(R.layout.item_picker, box, false)
                row.findViewById<TextView>(R.id.itemText)?.text = label
                row.findViewById<View>(R.id.itemCheck)?.visibility =
                    if (index == selected) View.VISIBLE else View.INVISIBLE
                row.setOnClickListener {
                    dialog.dismiss()
                    onPick(index)
                }
                box.addView(row)
            }
        }
        dialog.findViewById<View>(R.id.dlgCancel)?.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    /**
     * 单行输入对话框。
     * [validate] 返回错误文案（null 表示通过）。
     */
    fun input(
        context: Context,
        title: String,
        initial: String,
        hint: String = "",
        tip: String = "",
        numeric: Boolean = false,
        password: Boolean = false,
        validate: (String) -> String? = { null },
        onOk: (String) -> Unit
    ) {
        val dialog = Dialog(context)
        dialog.setContentView(R.layout.dialog_input)
        val window = dialog.window ?: return
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        val width = context.resources.displayMetrics.widthPixels - dp(context, 64)
        window.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT)

        val input = dialog.findViewById<EditText>(R.id.dlgInput)
        val error = dialog.findViewById<TextView>(R.id.dlgError)
        val tipView = dialog.findViewById<TextView>(R.id.dlgTip)
        dialog.findViewById<TextView>(R.id.dlgTitle)?.text = title

        if (tip.isNotEmpty()) {
            tipView?.text = tip
            tipView?.visibility = View.VISIBLE
        }

        input?.apply {
            // 先定输入类型，再填文字，否则切换成密码类型时光标会被重置到最前
            inputType = when {
                numeric -> InputType.TYPE_CLASS_NUMBER
                password -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                else -> InputType.TYPE_CLASS_TEXT
            }
            setText(initial)
            this.hint = hint
            setSelection(text.length)
        }

        dialog.findViewById<View>(R.id.dlgCancel)?.setOnClickListener { dialog.dismiss() }
        dialog.findViewById<View>(R.id.dlgOk)?.setOnClickListener {
            val value = input?.text?.toString()?.trim().orEmpty()
            val message = validate(value)
            if (message != null) {
                error?.text = message
                error?.visibility = View.VISIBLE
                return@setOnClickListener
            }
            dialog.dismiss()
            onOk(value)
        }

        dialog.setOnShowListener {
            input?.requestFocus()
            // 弹窗真正显示后把光标放到末尾，避免部分输入法把它复位到开头
            input?.setSelection(input.text.length)
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }
        dialog.show()
    }
}