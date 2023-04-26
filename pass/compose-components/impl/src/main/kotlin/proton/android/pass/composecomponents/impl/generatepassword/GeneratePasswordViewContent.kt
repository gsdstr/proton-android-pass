package proton.android.pass.composecomponents.impl.generatepassword

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material.Slider
import androidx.compose.material.SliderDefaults
import androidx.compose.material.Surface
import androidx.compose.material.Switch
import androidx.compose.material.SwitchDefaults
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.proton.core.compose.theme.ProtonTheme
import me.proton.core.compose.theme.subheadline
import proton.android.pass.commonui.api.PassTheme
import proton.android.pass.commonui.api.PassTypography
import proton.android.pass.commonui.api.ThemePreviewProvider
import proton.android.pass.commonui.api.toPasswordAnnotatedString
import proton.android.pass.composecomponents.impl.R

@Composable
fun GeneratePasswordViewContent(
    modifier: Modifier = Modifier,
    state: GeneratePasswordUiState,
    onSpecialCharactersChange: (Boolean) -> Unit,
    onLengthChange: (Int) -> Unit
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        val annotatedString = state.password.toPasswordAnnotatedString(
            digitColor = PassTheme.colors.loginInteractionNormMajor2,
            symbolColor = PassTheme.colors.aliasInteractionNormMajor2,
            letterColor = PassTheme.colors.textNorm
        )
        Text(
            modifier = Modifier
                .height(100.dp)
                .wrapContentHeight(align = Alignment.CenterVertically),
            text = annotatedString,
            style = ProtonTheme.typography.subheadline
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.character_count, state.length),
                color = PassTheme.colors.textNorm,
                style = PassTypography.body3Regular,
                fontSize = 16.sp
            )
            var sliderPosition by remember { mutableStateOf(state.length.toFloat()) }
            val valueRange = remember { 4.toFloat()..64.toFloat() }
            Slider(
                modifier = Modifier.weight(1f),
                value = sliderPosition,
                valueRange = valueRange,
                colors = SliderDefaults.colors(
                    thumbColor = PassTheme.colors.loginInteractionNormMajor1,
                    activeTrackColor = PassTheme.colors.loginInteractionNormMajor1,
                    inactiveTrackColor = PassTheme.colors.loginInteractionNormMinor1
                ),
                onValueChange = { sliderPosition = it },
                onValueChangeFinished = { onLengthChange(sliderPosition.toInt()) }
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.special_characters),
                color = PassTheme.colors.textNorm,
                style = PassTypography.body3Regular,
                fontSize = 16.sp
            )
            Switch(
                checked = state.hasSpecialCharacters,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = PassTheme.colors.loginInteractionNormMajor1,
                ),
                onCheckedChange = { onSpecialCharactersChange(it) }
            )
        }
    }
}

@Preview
@Composable
fun GeneratePasswordViewContentThemePreview(
    @PreviewParameter(ThemePreviewProvider::class) isDarkMode: Boolean
) {
    PassTheme(isDark = isDarkMode) {
        Surface {
            GeneratePasswordViewContent(
                state = GeneratePasswordUiState(
                    password = "a1b!c_d3e#fg",
                    length = 12,
                    hasSpecialCharacters = true
                ),
                onSpecialCharactersChange = {},
                onLengthChange = {}
            )
        }
    }
}
