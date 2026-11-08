package xyz.gaon.codeclear.translation

/** Release-owned data: changing any wording, name, or matching rule requires a new revision. */
object StandardTranslationCatalog {
    const val REVISION = "2026-11-08.2"
    data class Reference(
        val id: String,
        val url: String,
        val edition: String,
        val translationOrigin: String
    )
    val references = listOf(
        Reference(
            "kr-general",
            "https://www.law.go.kr/행정규칙/재난문자방송기준및운영규정",
            "Annex 2, user-supplied 18-page PDF",
            "Application-authored English adaptation"
        ),
        Reference(
            "kr-kma",
            "https://www.law.go.kr/LSW/flDownload.do?flSeq=164566155&bylClsCd=200201",
            "KMA 1182, 2026-05-28, Annex 2",
            "Application-authored English adaptation"
        ),
        Reference(
            "kr-civil",
            "https://www.law.go.kr/LSW/flDownload.do?flSeq=160780739&bylClsCd=200201",
            "MOIS 355, 2026-01-05, Annex 9",
            "Application-authored English adaptation"
        ),
        Reference(
            "us-fcc",
            "https://docs.fcc.gov/public/attachments/DA-25-12A1.pdf",
            "DA 25-12, 2025-01-08, pp.30,55",
            "FCC English/Korean wording; variables preserved"
        ),
        Reference(
            "jp-jma",
            "https://www.jma.go.jp/jma/press/1510/29a/tagengo20151029_dictionary.pdf",
            "2015-10-29, printed pp.2,32-33",
            "JMA Japanese/English expression pairs"
        ),
        Reference(
            "kr-names",
            "https://www.law.go.kr/LSW/flDownload.do?flSeq=160780671&bylClsCd=200201",
            "MOIS 355, Annex 4; agency official English names",
            "Prepared administrative and agency names"
        ),
        Reference(
            "us-names",
            "https://www.usgs.gov/",
            "USGS and National Weather Service agency names",
            "Original English names preserved"
        )
    )
    private val names by lazy { StandardNameCatalog(preparedNames()) }
    val templates: List<StandardMessageTemplate> by lazy {
        koreanStandardTemplates() +
            internationalStandardTemplates()
    }
    private val matcher by lazy { StandardMessageTranslator(templates, REVISION, names) }

    fun translate(body: String, target: TranslationTarget): StandardMessageTranslation? =
        matcher.translate(body, target)
    fun isCurrent(attribution: StandardTranslationAttribution): Boolean =
        attribution.catalogRevision == REVISION && templates.any { it.id == attribution.templateId }

    private fun preparedNames(): List<StandardNameCatalog.Entry> = buildList {
        val regions = listOf(
            Triple("서울특별시", "서울", "Seoul"), Triple("부산광역시", "부산", "Busan"),
            Triple("대구광역시", "대구", "Daegu"), Triple("인천광역시", "인천", "Incheon"),
            Triple("광주광역시", "", "Gwangju"), Triple("대전광역시", "대전", "Daejeon"),
            Triple("울산광역시", "울산", "Ulsan"), Triple("세종특별자치시", "세종", "Sejong"),
            Triple("경기도", "경기", "Gyeonggi-do"), Triple("강원특별자치도", "강원", "Gangwon-do"),
            Triple("충청북도", "충북", "Chungcheongbuk-do"), Triple("충청남도", "충남", "Chungcheongnam-do"),
            Triple("전북특별자치도", "전북", "Jeonbuk-do"), Triple("전라남도", "전남", "Jeollanam-do"),
            Triple("경상북도", "경북", "Gyeongsangbuk-do"), Triple("경상남도", "경남", "Gyeongsangnam-do"),
            Triple("제주특별자치도", "", "Jeju-do")
        )
        regions.forEachIndexed { index, (native, alias, english) ->
            add(
                StandardNameCatalog.Entry(
                    "kr-area-$index",
                    "KR",
                    StandardSlotKind.AREA,
                    "ko",
                    native,
                    setOf(alias).filter {
                        it.isNotEmpty()
                    }.toSet(),
                    mapOf("en" to english),
                    "kr-names"
                )
            )
        }
        listOf(
            "기상청" to "Korea Meteorological Administration",
            "행정안전부" to "Ministry of the Interior and Safety",
            "서울특별시" to "Seoul Metropolitan Government",
            "부산광역시" to "Busan Metropolitan City"
        )
            .forEachIndexed { index, (native, english) ->
                add(
                    StandardNameCatalog.Entry(
                        "kr-agency-$index",
                        "KR",
                        StandardSlotKind.AGENCY,
                        "ko",
                        native,
                        translations = mapOf("en" to english),
                        referenceId = "kr-names"
                    )
                )
            }
        listOf("USGS", "U.S. Geological Survey", "National Weather Service", "NWS").forEachIndexed {
                index,
                name
            ->
            add(
                StandardNameCatalog.Entry(
                    "us-agency-$index",
                    "US",
                    StandardSlotKind.AGENCY,
                    "en",
                    name,
                    translations = emptyMap(),
                    referenceId = "us-names"
                )
            )
        }
    }
}
