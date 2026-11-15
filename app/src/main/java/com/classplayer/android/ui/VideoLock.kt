package com.classplayer.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.classplayer.android.ui.theme.Muted

private val Error = Color(0xFFF87171)

@Composable
private fun PwField(value: String, label: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange, singleLine = true, label = { Text(label) },
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
    )
}

/** 비밀번호 한 번 넣기(잠금 풀기·비밀번호 지우기). onSubmit이 false면 "틀렸습니다". */
@Composable
fun PasswordAskDialog(title: String, desc: String, confirmText: String, onDismiss: () -> Unit, onSubmit: (String) -> Boolean) {
    var pw by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(desc, color = Muted, fontSize = 13.sp)
                PwField(pw, "비밀번호") { pw = it; err = null }
                err?.let { Text(it, color = Error, fontSize = 13.sp) }
            }
        },
        confirmButton = { TextButton(onClick = { if (onSubmit(pw)) onDismiss() else { err = "비밀번호가 틀렸습니다"; pw = "" } }) { Text(confirmText) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

/** 비밀번호 정하기/바꾸기. onSubmit은 오류 문구(성공이면 null)를 돌려준다. */
@Composable
fun PasswordSetDialog(hasPassword: Boolean, onDismiss: () -> Unit, onSubmit: (current: String?, new: String, confirm: String) -> String?) {
    var cur by remember { mutableStateOf("") }
    var new by remember { mutableStateOf("") }
    var conf by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (hasPassword) "비밀번호 바꾸기" else "영상 잠금 비밀번호 정하기") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("4자 이상(숫자 권장). 잊어버리면 앱 데이터를 지워야 하니 꼭 기억해 두세요.", color = Muted, fontSize = 13.sp)
                if (hasPassword) PwField(cur, "현재 비밀번호") { cur = it; err = null }
                PwField(new, "새 비밀번호") { new = it; err = null }
                PwField(conf, "새 비밀번호 확인") { conf = it; err = null }
                err?.let { Text(it, color = Error, fontSize = 13.sp) }
            }
        },
        confirmButton = { TextButton(onClick = { val e = onSubmit(if (hasPassword) cur else null, new, conf); if (e == null) onDismiss() else err = e }) { Text("저장") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}
