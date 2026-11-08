package xyz.gaon.codeclear.translation

/** Exact official expression pairs, not a claim that all senders use these wordings. */
internal fun internationalStandardTemplates(): List<StandardMessageTemplate> = buildList {
    add(
        StandardMessageTemplate(
            "us-fcc-earthquake",
            "en",
            "US",
            "EARTHQUAKE DETECTED! Drop, Cover, Hold On. Protect Yourself. {agency} {url}",
            mapOf("ko" to "지진 감지! 엎드립니다, 머리를 보호합니다, 신체를 지지합니다. 자신을 보호하십시오. {agency} {url}"),
            mapOf("agency" to StandardSlotKind.AGENCY, "url" to StandardSlotKind.URL),
            preserveNames = true,
            referenceId = "us-fcc"
        )
    )
    val pairs = listOf(
        Triple(
            "earthquake",
            "地震です 落ち着いて 身を守ってください",
            "An earthquake has just occurred. Stay calm and secure your personal safety."
        ),
        Triple("earthquake-cancelled", "先ほど発表した緊急地震速報を取り消します", "JMA has cancelled the EEW."),
        Triple(
            "tsunami-warning",
            "津波警報が発表されました 海岸付近の方は高台に避難してください",
            "Tsunami Warning issued Evacuate immediately to a safer place such as high ground or an evacuation building."
        ),
        Triple(
            "tsunami-advisory",
            "津波注意報が発表されました 海岸付近の方は海岸から離れてください",
            "Tsunami Advisory issued Get out of the water and leave coastal regions immediately."
        )
    )
    pairs.forEach { (id, source, target) ->
        add(
            StandardMessageTemplate(
                "jp-jma-$id",
                "ja",
                "JP",
                source,
                mapOf("en" to target),
                referenceId = "jp-jma"
            )
        )
    }
    listOf(
        "大津波警報" to "Major Tsunami Warning",
        "津波警報" to "Tsunami Warning",
        "津波注意報" to "Tsunami Advisory"
    )
        .forEachIndexed { index, (source, target) ->
            listOf(
                "更新" to "updated",
                "解除" to "lifted",
                "一部解除" to "partially lifted",
                "取り消" to "cancelled"
            )
                .forEachIndexed { action, (japanese, english) ->
                    add(
                        StandardMessageTemplate(
                            "jp-jma-tsunami-$index-$action",
                            "ja",
                            "JP",
                            source + "が" + japanese + "されました",
                            mapOf("en" to "$target $english."),
                            referenceId = "jp-jma"
                        )
                    )
                }
        }
}
