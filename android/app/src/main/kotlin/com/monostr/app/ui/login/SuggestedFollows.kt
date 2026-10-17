package com.monostr.app.ui.login

import androidx.annotation.StringRes
import com.monostr.app.R

/** One account of the fixed follow list; [name] is only a fallback label before login (spec 2026-10-17 §5, §11). */
data class SuggestedFollow(val pubkey: String, val name: String, @StringRes val who: Int)

/** The 14 suggested accounts in the approved order (spec 2026-10-17 §5). */
object SuggestedFollows {
    val all: List<SuggestedFollow> = listOf(
        SuggestedFollow(
            "1601fcacdf227ddda10e33e71917930b49be436b43bdd030b799948a9f274ed3",
            "monostr",
            R.string.create_who_monostr,
        ),
        SuggestedFollow(
            "0d7ceca9e000e711e263bc14a2216ab74968a9e903eba214a714300adede5a20",
            "Schmidt",
            R.string.create_who_schmidt,
        ),
        SuggestedFollow(
            "3bf0c63fcb93463407af97a5e5ee64fa883d107ef9e558472c4eb9aaaefa459d",
            "fiatjaf",
            R.string.create_who_fiatjaf,
        ),
        SuggestedFollow(
            "32e1827635450ebb3c5a7d12c1f8e7b2b514439ac10a67eef3d9fd9c5c68e245",
            "jb55",
            R.string.create_who_jb55,
        ),
        SuggestedFollow(
            "460c25e682fda7832b52d1f22d3d22b3176d972f60dcdc3212ed8c92ef85065c",
            "VitorPamplona",
            R.string.create_who_vitor,
        ),
        SuggestedFollow(
            "7579076d9aff0a4cfdefa7e2045f2486c7e5d8bc63bfc6b45397233e1bbfcb19",
            "greenart7c3",
            R.string.create_who_greenart,
        ),
        SuggestedFollow(
            "266815e0c9210dfa324c6cba3573b14bee49da4209a9456f9484e5106cd408a5",
            "hzrd149",
            R.string.create_who_hzrd,
        ),
        SuggestedFollow(
            "3f770d65d3a764a9c5cb503ae123e62ec7598ad035d836e2a810f3877a745b24",
            "Derek Ross",
            R.string.create_who_derek,
        ),
        SuggestedFollow(
            "04c915daefee38317fa734444acee390a8269fe5810b2241e5e6dd343dfbecc9",
            "ODELL",
            R.string.create_who_odell,
        ),
        SuggestedFollow(
            "82341f882b6eabcd2ba7f1ef90aad961cf074af15b9ef44a09f9d2a8fbfbe6a2",
            "jack",
            R.string.create_who_jack,
        ),
        SuggestedFollow(
            "78ce6faa72264387284e647ba6938995735ec8c7d5c5a65737e55130f026307d",
            "Zapstore",
            R.string.create_who_zapstore,
        ),
        SuggestedFollow(
            "0f22c06eac1002684efcc68f568540e8342d1609d508bcd4312c038e6194f8b6",
            "nos.social",
            R.string.create_who_nos,
        ),
        SuggestedFollow(
            "dbe791bf6b9221eaf517e43c030cdff6c0c7e9b561c6228773e194d08eb8ddf7",
            "MoneroResearchLab",
            R.string.create_who_mrl,
        ),
        SuggestedFollow(
            "a6038971d542950c13c4da295bcaa37c7985195b12b0fa95f6886fe608050bb9",
            "OrangeFren",
            R.string.create_who_orangefren,
        ),
    )
}
