package proton.android.pass.featureitemdetail.impl.alias

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import proton.android.pass.commonui.api.PassTheme
import proton.android.pass.commonui.api.asAnnotatedString
import proton.android.pass.composecomponents.impl.icon.ForwardIcon
import proton.android.pass.composecomponents.impl.item.SectionTitle
import proton.android.pass.composecomponents.impl.item.placeholder
import proton.android.pass.featureitemdetail.impl.R
import proton.android.pass.featureitemdetail.impl.common.SectionSubtitle
import proton.pass.domain.AliasMailbox

@Composable
fun AliasMailboxesRow(
    modifier: Modifier = Modifier,
    mailboxes: List<AliasMailbox>,
    isLoading: Boolean
) {
    if (!isLoading && mailboxes.isEmpty()) return
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        ForwardIcon(tint = PassTheme.colors.aliasInteractionNormMajor1)
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SectionTitle(text = stringResource(R.string.alias_detail_field_mailboxes_title))
            if (mailboxes.isEmpty() && isLoading) {
                SectionSubtitle(
                    modifier = Modifier
                        .fillMaxWidth()
                        .placeholder(),
                    text = AnnotatedString("")
                )
            } else {
                mailboxes.forEach { mailbox ->
                    SectionSubtitle(text = mailbox.email.asAnnotatedString())
                }
            }
        }
    }
}
