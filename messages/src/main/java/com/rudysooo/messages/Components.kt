package com.rudysooo.messages

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private fun fmt(pattern: String, ms: Long) = SimpleDateFormat(pattern, Locale.getDefault()).format(Date(ms))

fun fmtTime(ms: Long) = fmt("HH:mm", ms)

fun fmtList(ms: Long): String {
    val days = (System.currentTimeMillis() - ms) / DateUtils.DAY_IN_MILLIS
    return when {
        DateUtils.isToday(ms) -> fmtTime(ms)
        days < 1 || isYesterday(ms) -> "Hier"
        days < 6 -> fmt("EEE", ms).replaceFirstChar { it.uppercase() }
        sameYear(ms) -> fmt("d MMM", ms)
        else -> fmt("dd/MM/yy", ms)
    }
}

fun dayLabel(ms: Long): String = when {
    DateUtils.isToday(ms) -> "Aujourd'hui"
    isYesterday(ms) -> "Hier"
    sameYear(ms) -> fmt("EEEE d MMMM", ms).replaceFirstChar { it.uppercase() }
    else -> fmt("d MMMM yyyy", ms)
}

fun fullDate(ms: Long) = fmt("EEEE d MMMM yyyy 'à' HH:mm", ms).replaceFirstChar { it.uppercase() }

fun dayKey(ms: Long) = fmt("yyyyMMdd", ms)

private fun isYesterday(ms: Long) = DateUtils.isToday(ms + DateUtils.DAY_IN_MILLIS)

private fun sameYear(ms: Long): Boolean {
    val a = Calendar.getInstance(); val b = Calendar.getInstance().apply { timeInMillis = ms }
    return a.get(Calendar.YEAR) == b.get(Calendar.YEAR)
}

@Composable
fun Avatar(title: String, size: Dp = 48.dp, selected: Boolean = false) {
    val letter = title.firstOrNull { it.isLetter() }
    val color = if (selected) MaterialTheme.colorScheme.primary
    else AvatarColors[(title.hashCode() and 0x7fffffff) % AvatarColors.size]
    Box(Modifier.size(size).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
        when {
            selected -> Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.onPrimary)
            letter != null -> Text(letter.uppercase(), color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = (size.value * 0.42f).sp)
            else -> Icon(Icons.Default.Person, null, tint = Color.White)
        }
    }
}
