# -*- coding: utf-8 -*-
"""移除关于页的「更新」「捐赠」区块，插入「致谢」区块。"""
import io

p = r"D:\project\mpvEx-master\app\src\main\java\app\marlboroadvance\mpvex\ui\preferences\AboutScreen.kt"
src = io.open(p, encoding="utf-8").read()
lines = src.split("\n")

i_up = next(i for i, l in enumerate(lines) if "// Updates Section" in l)
i_don = next(i for i, l in enumerate(lines) if "// Donate Section" in l)
i_end = next(i for i, l in enumerate(lines) if i > i_don and "Spacer(Modifier.height(12.dp))" in l)

new_block = '''        // Acknowledgments Section
        PreferenceSectionHeader(
          title = stringResource(id = R.string.i18n_ack_title),
        )

        PreferenceCard {
          Row(
            modifier =
              Modifier
                .fillMaxWidth()
                .clickable {
                  context.startActivity(
                    Intent(
                      Intent.ACTION_VIEW,
                      context.getString(R.string.github_repo_url).toUri(),
                    ),
                  )
                }
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Icon(
              imageVector = Icons.Filled.Code,
              contentDescription = null,
              modifier = Modifier.size(24.dp),
              tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
              Text(
                text = stringResource(id = R.string.i18n_ack_mpvex_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
              )
              Text(
                text = stringResource(id = R.string.i18n_ack_mpvex_summary),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline,
              )
              Text(
                text = stringResource(id = R.string.i18n_ack_tap_to_open),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
              )
            }
          }
        }

'''

out = lines[:i_up] + new_block.split("\n") + lines[i_end:]
io.open(p, "w", encoding="utf-8", newline="\n").write("\n".join(out))
print("removed lines %d..%d, inserted %d lines" % (i_up, i_end - 1, len(new_block.split("\n"))))
