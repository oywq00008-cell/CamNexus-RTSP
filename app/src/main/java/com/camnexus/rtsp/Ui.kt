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
        dialog.findViewById<TextView>(R.id.dlgTitle)?.text = title

        input?.apply {
            setText(initial)
            this.hint = hint
            setSelection(text.length)
            inputType = when {
                numeric -> InputType.TYPE_CLASS_NUMBER
                password -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                else -> InputType.TYPE_CLASS_TEXT
            }
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
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }
        dialog.show()
    }
}