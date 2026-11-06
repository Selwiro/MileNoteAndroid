package com.milenote.android.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.milenote.android.ui.theme.MileNoteDimens
import com.milenote.android.ui.theme.MileNoteShapes
import com.milenote.android.ui.theme.MileNoteTheme

/**
 * 表单字段（OutlinedTextField 封装）：标签 / 错误 / 单位后缀 / 键盘类型 / 前后图标。
 *
 * 统一 56dp 高、12dp 圆角、`bodyLarge` 输入字号、错误态 `error` 色 + `errorContainer` 描边，
 * 与 Web 版表单字段语义一致（金额、里程、加油量、油耗、地点、备注等）。
 *
 * ```kotlin
 * FormField(
 *     value = liters,
 *     onValueChange = { liters = it },
 *     label = "加油量",
 *     suffix = "升",
 *     keyboardType = KeyboardType.Decimal,
 *     trailing = { IconButton(...) { Icon(MileNoteIcons.Camera, "拍照识别") } },
 * )
 * ```
 *
 * @param value 当前文本。
 * @param onValueChange 文本变化回调。
 * @param label 浮动标签。
 * @param placeholder 占位文字。
 * @param supportingText 支持文字（正常说明；[isError] 时作为错误提示）。
 * @param isError 是否错误态。
 * @param suffix 单位后缀（如 L / km / 元），显示在输入区右侧。
 * @param keyboardType 键盘类型。
 * @param singleLine 是否单行。
 * @param minLines 多行时的最小行数。
 * @param leadingIcon 前置图标。
 * @param trailing 尾部内容（相机按钮 / 清除按钮 / 下拉箭头等）。
 * @param visualTransformation 文本变换（密码）。
 * @param enabled 是否可编辑。
 * @param readOnly 是否只读。
 */
@Composable
fun FormField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    supportingText: String? = null,
    isError: Boolean = false,
    suffix: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    singleLine: Boolean = true,
    minLines: Int = 1,
    leadingIcon: ImageVector? = null,
    trailing: (@Composable () -> Unit)? = null,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    shape: Shape = MileNoteShapes.medium,
) {
    val scheme = MaterialTheme.colorScheme
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        enabled = enabled,
        readOnly = readOnly,
        textStyle = MaterialTheme.typography.bodyLarge,
        label = { Text(label, style = MaterialTheme.typography.bodyMedium) },
        placeholder = placeholder?.let {
            { Text(it, style = MaterialTheme.typography.bodyMedium) }
        },
        leadingIcon = leadingIcon?.let {
            {
                Icon(
                    imageVector = it,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
            }
        },
        trailingIcon = trailing,
        suffix = suffix?.let {
            { Text(it, style = MaterialTheme.typography.labelMedium) }
        },
        supportingText = supportingText?.let {
            { Text(it, style = MaterialTheme.typography.bodySmall) }
        },
        isError = isError,
        visualTransformation = visualTransformation,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        singleLine = singleLine,
        minLines = minLines,
        shape = shape,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = scheme.primary,
            unfocusedBorderColor = scheme.outlineVariant,
            focusedLabelColor = scheme.primary,
            unfocusedLabelColor = scheme.onSurfaceVariant,
            cursorColor = scheme.primary,
            errorBorderColor = scheme.error,
            errorLabelColor = scheme.error,
            errorSupportingTextColor = scheme.error,
        ),
    )
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun FormFieldPreview() {
    MileNoteTheme {
        androidx.compose.foundation.layout.Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
        ) {
            FormField(
                value = "266.00",
                onValueChange = {},
                label = "金额",
                suffix = "元",
                keyboardType = KeyboardType.Decimal,
            )
            FormField(
                value = "",
                onValueChange = {},
                label = "加油量",
                suffix = "升",
                keyboardType = KeyboardType.Decimal,
                supportingText = "两次加满后即可计算油耗",
            )
            FormField(
                value = "abc",
                onValueChange = {},
                label = "总里程",
                isError = true,
                supportingText = "里程必须是有效非负数",
                keyboardType = KeyboardType.Number,
                leadingIcon = com.milenote.android.ui.icons.MileNoteIcons.Road,
            )
        }
    }
}
