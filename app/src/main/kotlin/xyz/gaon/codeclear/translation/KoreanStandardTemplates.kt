package xyz.gaon.codeclear.translation

/** Korean source wording is official; the complete English renderings are application adaptations. */
internal fun koreanStandardTemplates(): List<StandardMessageTemplate> = listOf(
    StandardMessageTemplate(
        "kr-general-rain-advisory",
        "ko",
        "KR",
        "오늘 {time} {area}지역 호우주의보 발효. 하천 주변 산책로, 계곡, 급경사지, 농수로 등 위험지역 출입 금지, 하천 범람에 유의. [행정안전부]",
        mapOf(
            "en" to
                "Today {time}, {area}: Heavy rain advisory. Do not enter hazardous areas including riverside paths, valleys, steep slopes, or irrigation channels. Watch for overflowing rivers. [Ministry of the Interior and Safety]"
        ),
        mapOf("time" to StandardSlotKind.TIME, "area" to StandardSlotKind.AREA),
        referenceId = "kr-general"
    ),
    StandardMessageTemplate(
        "kr-general-rain-warning",
        "ko",
        "KR",
        "오늘 {time} {area}지역 호우경보 발효. 하천 주변, 계곡, 급경사지, 농수로 등 위험지역 출입 금지, 대피권고를 받으면 즉시 대피하세요. [행정안전부]",
        mapOf(
            "en" to
                "Today {time}, {area}: Heavy rain warning. Do not enter hazardous areas including riversides, valleys, steep slopes, or irrigation channels. Evacuate immediately if advised. [Ministry of the Interior and Safety]"
        ),
        mapOf("time" to StandardSlotKind.TIME, "area" to StandardSlotKind.AREA),
        referenceId = "kr-general"
    ),
    StandardMessageTemplate(
        "kr-general-typhoon-warning",
        "ko",
        "KR",
        "오늘 {time} {area}지역 태풍경보 발효. 실내에 머무르세요. 해안가, 급경사지 등 위험지역 출입 금지, 대피권고를 받으면 즉시 대피하세요. [행정안전부]",
        mapOf(
            "en" to
                "Today {time}, {area}: Typhoon warning. Stay indoors. Do not enter hazardous areas including coasts or steep slopes. Evacuate immediately if advised. [Ministry of the Interior and Safety]"
        ),
        mapOf("time" to StandardSlotKind.TIME, "area" to StandardSlotKind.AREA),
        referenceId = "kr-general"
    ),
    StandardMessageTemplate(
        "kr-general-surge-warning",
        "ko",
        "KR",
        "오늘 {time} {area}지역 폭풍해일경보 발효. 해안가 저지대 침수가 예상되니, 해당 지역 주민은 높은 지대나 건물 위층으로 대피하세요. [행정안전부]",
        mapOf(
            "en" to
                "Today {time}, {area}: Storm surge warning. Flooding is expected in low-lying coastal areas. Residents there should evacuate to higher ground or upper floors of buildings. [Ministry of the Interior and Safety]"
        ),
        mapOf("time" to StandardSlotKind.TIME, "area" to StandardSlotKind.AREA),
        referenceId = "kr-general"
    ),
    StandardMessageTemplate(
        "kr-kma-land-advice",
        "ko",
        "KR",
        "{date} {area} {distance}km 지역 규모 {magnitude} 지진발생/추가 지진 발생상황에 유의 바람 [기상청]",
        mapOf(
            "en" to
                "{date}: Magnitude {magnitude} earthquake, land area {distance}km from {area}. Watch for further earthquakes. [Korea Meteorological Administration]"
        ),
        mapOf(
            "date" to StandardSlotKind.DATE_TIME,
            "area" to StandardSlotKind.AREA,
            "distance" to StandardSlotKind.DISTANCE,
            "magnitude" to StandardSlotKind.MAGNITUDE
        ),
        referenceId = "kr-kma"
    ),
    StandardMessageTemplate(
        "kr-kma-land-urgent",
        "ko",
        "KR",
        "{date} {area} {distance}km 지역 M{magnitude} 지진/낙하물,여진주의 국민안전24 참고대응 Earthquake[기상청]",
        mapOf(
            "en" to
                "{date}: Magnitude {magnitude} earthquake, land area {distance}km from {area}. Beware of falling objects and aftershocks. Follow guidance on 국민안전24 (National Safety 24). Earthquake [Korea Meteorological Administration]"
        ),
        mapOf(
            "date" to StandardSlotKind.DATE_TIME,
            "area" to StandardSlotKind.AREA,
            "distance" to StandardSlotKind.DISTANCE,
            "magnitude" to StandardSlotKind.MAGNITUDE
        ),
        referenceId = "kr-kma"
    ),
    StandardMessageTemplate(
        "kr-kma-sea-advice",
        "ko",
        "KR",
        "{date} {area} {distance}km 해역 규모 {magnitude} 지진발생/추가 지진 발생상황에 유의 바람 [기상청]",
        mapOf(
            "en" to
                "{date}: Magnitude {magnitude} earthquake, sea area {distance}km from {area}. Watch for further earthquakes. [Korea Meteorological Administration]"
        ),
        mapOf(
            "date" to StandardSlotKind.DATE_TIME,
            "area" to StandardSlotKind.AREA,
            "distance" to StandardSlotKind.DISTANCE,
            "magnitude" to StandardSlotKind.MAGNITUDE
        ),
        referenceId = "kr-kma"
    ),
    StandardMessageTemplate(
        "kr-kma-sea-urgent",
        "ko",
        "KR",
        "{date} {area} {distance}km 해역 M{magnitude} 지진/낙하물,여진주의 국민안전24 참고대응 Earthquake[기상청]",
        mapOf(
            "en" to
                "{date}: Magnitude {magnitude} earthquake, sea area {distance}km from {area}. Beware of falling objects and aftershocks. Follow guidance on 국민안전24 (National Safety 24). Earthquake [Korea Meteorological Administration]"
        ),
        mapOf(
            "date" to StandardSlotKind.DATE_TIME,
            "area" to StandardSlotKind.AREA,
            "distance" to StandardSlotKind.DISTANCE,
            "magnitude" to StandardSlotKind.MAGNITUDE
        ),
        referenceId = "kr-kma"
    ),
    StandardMessageTemplate(
        "kr-kma-intensity",
        "ko",
        "KR",
        "{date} {area} 일대 예상진도 {intensity}의 강한 진동 감지/안전주의, 기상청 추가정보 확인 바람 Earthquake[기상청]",
        mapOf(
            "en" to
                "{date}: Strong shaking detected around {area}, expected seismic intensity {intensity}. Take care and check further information from the Korea Meteorological Administration. Earthquake [Korea Meteorological Administration]"
        ),
        mapOf(
            "date" to StandardSlotKind.DATE_TIME,
            "area" to StandardSlotKind.AREA,
            "intensity" to StandardSlotKind.INTENSITY
        ),
        referenceId = "kr-kma"
    ),
    StandardMessageTemplate(
        "kr-kma-tsunami",
        "ko",
        "KR",
        "{date} {area}지역 지진해일 특보 발령, 선박대피, 해변주민높은지대대피Tsunami[기상청]",
        mapOf(
            "en" to
                "{date}: Tsunami alert issued for {area}. Evacuate vessels; coastal residents should evacuate to higher ground. Tsunami [Korea Meteorological Administration]"
        ),
        mapOf("date" to StandardSlotKind.DATE_TIME, "area" to StandardSlotKind.AREA),
        referenceId = "kr-kma"
    ),
    StandardMessageTemplate(
        "kr-kma-tsunami-info",
        "ko",
        "KR",
        "{date} {area}지역 지진해일정보 발표, 해안가안전주의,해일지속대비최신정보확인[기상청]",
        mapOf(
            "en" to
                "{date}: Tsunami information issued for {area}. Take care near the coast and check the latest information in case tsunami activity continues. [Korea Meteorological Administration]"
        ),
        mapOf("date" to StandardSlotKind.DATE_TIME, "area" to StandardSlotKind.AREA),
        referenceId = "kr-kma"
    ),
    StandardMessageTemplate(
        "kr-civil-aircraft-preliminary",
        "ko",
        "KR",
        "{time} 북한 항공기 침범, {area}, {area2}지역 경계경보, 지하시설 또는 건물 내로 대피. EVACUATION, AIR RAID [{agency}]",
        mapOf(
            "en" to
                "{time}, {area}, {area2}: North Korean aircraft intrusion. Preliminary air-raid warning. Seek shelter underground or inside a building. EVACUATION, AIR RAID [{agency}]"
        ),
        mapOf(
            "time" to StandardSlotKind.TIME,
            "area" to StandardSlotKind.AREA,
            "area2" to StandardSlotKind.AREA,
            "agency" to StandardSlotKind.AGENCY
        ),
        referenceId = "kr-civil"
    ),
    StandardMessageTemplate(
        "kr-civil-missile-preliminary",
        "ko",
        "KR",
        "{time} 북한 미사일 발사, {area}, {area2}지역 경계경보, 낙하물 유의, 야외활동 자제. MISSILES ALERT [{agency}]",
        mapOf(
            "en" to
                "{time}, {area}, {area2}: North Korean missile launch. Preliminary air-raid warning. Beware of falling objects and avoid outdoor activities. MISSILES ALERT [{agency}]"
        ),
        mapOf(
            "time" to StandardSlotKind.TIME,
            "area" to StandardSlotKind.AREA,
            "area2" to StandardSlotKind.AREA,
            "agency" to StandardSlotKind.AGENCY
        ),
        referenceId = "kr-civil"
    ),
    StandardMessageTemplate(
        "kr-civil-aircraft-attack",
        "ko",
        "KR",
        "{time} 북한 항공기 침범, {area}, {area2}지역 공습경보, 지하시설 또는 건물 내로 대피. EVACUATION, AIR RAID [{agency}]",
        mapOf(
            "en" to
                "{time}, {area}, {area2}: North Korean aircraft intrusion. Air-raid warning. Seek shelter underground or inside a building. EVACUATION, AIR RAID [{agency}]"
        ),
        mapOf(
            "time" to StandardSlotKind.TIME,
            "area" to StandardSlotKind.AREA,
            "area2" to StandardSlotKind.AREA,
            "agency" to StandardSlotKind.AGENCY
        ),
        referenceId = "kr-civil"
    ),
    StandardMessageTemplate(
        "kr-civil-missile-attack",
        "ko",
        "KR",
        "{time} 북한 미사일 발사, {area}, {area2}지역 공습경보, 지하시설 또는 건물 내로 대피. EVACUATION, MISSILES ATTACK [{agency}]",
        mapOf(
            "en" to
                "{time}, {area}, {area2}: North Korean missile launch. Air-raid warning. Seek shelter underground or inside a building. EVACUATION, MISSILES ATTACK [{agency}]"
        ),
        mapOf(
            "time" to StandardSlotKind.TIME,
            "area" to StandardSlotKind.AREA,
            "area2" to StandardSlotKind.AREA,
            "agency" to StandardSlotKind.AGENCY
        ),
        referenceId = "kr-civil"
    ),
    StandardMessageTemplate(
        "kr-civil-artillery",
        "ko",
        "KR",
        "{time} 북한 포격, {area}, {area2}지역 공습경보, 지하시설 또는 건물 내로 대피. EVACUATION, ARTILLERY ATTACK [{agency}]",
        mapOf(
            "en" to
                "{time}, {area}, {area2}: North Korean artillery attack. Air-raid warning. Seek shelter underground or inside a building. EVACUATION, ARTILLERY ATTACK [{agency}]"
        ),
        mapOf(
            "time" to StandardSlotKind.TIME,
            "area" to StandardSlotKind.AREA,
            "area2" to StandardSlotKind.AREA,
            "agency" to StandardSlotKind.AGENCY
        ),
        referenceId = "kr-civil"
    ),
    StandardMessageTemplate(
        "kr-civil-biological",
        "ko",
        "KR",
        "{time} 생물학 공격, {area}, {area2}지역 화생방 경보, 호흡기 보호, 대피. EVACUATION, BIOLOGICAL ATTACK [{agency}]",
        mapOf(
            "en" to
                "{time}, {area}, {area2}: Biological attack. CBRN warning. Protect your breathing and evacuate. EVACUATION, BIOLOGICAL ATTACK [{agency}]"
        ),
        mapOf(
            "time" to StandardSlotKind.TIME,
            "area" to StandardSlotKind.AREA,
            "area2" to StandardSlotKind.AREA,
            "agency" to StandardSlotKind.AGENCY
        ),
        referenceId = "kr-civil"
    ),
    StandardMessageTemplate(
        "kr-civil-chemical",
        "ko",
        "KR",
        "{time} 화학 공격, {area}, {area2}지역 화생방경보, 호흡기 보호, 높은 곳 대피. EVACUATION, CHEMICAL ATTACK [{agency}]",
        mapOf(
            "en" to
                "{time}, {area}, {area2}: Chemical attack. CBRN warning. Protect your breathing and evacuate to a higher place. EVACUATION, CHEMICAL ATTACK [{agency}]"
        ),
        mapOf(
            "time" to StandardSlotKind.TIME,
            "area" to StandardSlotKind.AREA,
            "area2" to StandardSlotKind.AREA,
            "agency" to StandardSlotKind.AGENCY
        ),
        referenceId = "kr-civil"
    ),
    StandardMessageTemplate(
        "kr-civil-nuclear",
        "ko",
        "KR",
        "{time} 핵 폭발 {area}, {area2}지역 핵 경보, 지하시설 또는 건물 내로 대피. EVACUATION, NUCLEAR ATTACK [{agency}]",
        mapOf(
            "en" to
                "{time}, {area}, {area2}: Nuclear explosion. Nuclear warning. Seek shelter underground or inside a building. EVACUATION, NUCLEAR ATTACK [{agency}]"
        ),
        mapOf(
            "time" to StandardSlotKind.TIME,
            "area" to StandardSlotKind.AREA,
            "area2" to StandardSlotKind.AREA,
            "agency" to StandardSlotKind.AGENCY
        ),
        referenceId = "kr-civil"
    ),
    StandardMessageTemplate(
        "kr-civil-aircraft-preliminary-lifted",
        "ko",
        "KR",
        "북한 항공기 위험범위 이탈 {time}분 {area}지역 경계경보 해제, 일상으로 복귀 바랍니다. [{agency}]",
        mapOf(
            "en" to
                "North Korean aircraft have left the danger area. At {time}, the preliminary air-raid warning for {area} was lifted. Please resume normal activities. [{agency}]"
        ),
        mapOf(
            "time" to StandardSlotKind.TIME,
            "area" to StandardSlotKind.AREA,
            "agency" to StandardSlotKind.AGENCY
        ),
        referenceId = "kr-civil"
    ),
    StandardMessageTemplate(
        "kr-civil-aircraft-attack-lifted",
        "ko",
        "KR",
        "북한 항공기 위험범위 이탈 {time}분 {area}지역 공습경보 해제, 일상으로 복귀 바랍니다. [{agency}]",
        mapOf(
            "en" to
                "North Korean aircraft have left the danger area. At {time}, the air-raid warning for {area} was lifted. Please resume normal activities. [{agency}]"
        ),
        mapOf(
            "time" to StandardSlotKind.TIME,
            "area" to StandardSlotKind.AREA,
            "agency" to StandardSlotKind.AGENCY
        ),
        referenceId = "kr-civil"
    ),
    StandardMessageTemplate(
        "kr-civil-missile-preliminary-lifted",
        "ko",
        "KR",
        "북한 미사일 위험범위 이탈 {time}분 {area}지역 경계경보 해제, 일상으로 복귀 바랍니다. [{agency}]",
        mapOf(
            "en" to
                "North Korean missiles have left the danger area. At {time}, the preliminary air-raid warning for {area} was lifted. Please resume normal activities. [{agency}]"
        ),
        mapOf(
            "time" to StandardSlotKind.TIME,
            "area" to StandardSlotKind.AREA,
            "agency" to StandardSlotKind.AGENCY
        ),
        referenceId = "kr-civil"
    ),
    StandardMessageTemplate(
        "kr-civil-missile-attack-lifted",
        "ko",
        "KR",
        "북한 미사일 위험범위 이탈 {time}분 {area}지역 공습경보 해제, 일상으로 복귀 바랍니다. [{agency}]",
        mapOf(
            "en" to
                "North Korean missiles have left the danger area. At {time}, the air-raid warning for {area} was lifted. Please resume normal activities. [{agency}]"
        ),
        mapOf(
            "time" to StandardSlotKind.TIME,
            "area" to StandardSlotKind.AREA,
            "agency" to StandardSlotKind.AGENCY
        ),
        referenceId = "kr-civil"
    ),
    StandardMessageTemplate(
        "kr-civil-artillery-preliminary-lifted",
        "ko",
        "KR",
        "북한 포격 종료, {time}분 {area}지역 경계경보 해제, 일상으로 복귀 바랍니다. [{agency}]",
        mapOf(
            "en" to
                "North Korean artillery attack has ended. At {time}, the preliminary air-raid warning for {area} was lifted. Please resume normal activities. [{agency}]"
        ),
        mapOf(
            "time" to StandardSlotKind.TIME,
            "area" to StandardSlotKind.AREA,
            "agency" to StandardSlotKind.AGENCY
        ),
        referenceId = "kr-civil"
    ),
    StandardMessageTemplate(
        "kr-civil-artillery-attack-lifted",
        "ko",
        "KR",
        "북한 포격 종료, {time}분 {area}지역 공습경보 해제, 일상으로 복귀 바랍니다. [{agency}]",
        mapOf(
            "en" to
                "North Korean artillery attack has ended. At {time}, the air-raid warning for {area} was lifted. Please resume normal activities. [{agency}]"
        ),
        mapOf(
            "time" to StandardSlotKind.TIME,
            "area" to StandardSlotKind.AREA,
            "agency" to StandardSlotKind.AGENCY
        ),
        referenceId = "kr-civil"
    ),
    StandardMessageTemplate(
        "kr-civil-cbrn-lifted",
        "ko",
        "KR",
        "화생방 상황 종료. {time}분 {area}지역 화생방 경보 해제, 일상으로 복귀 바랍니다. [{agency}]",
        mapOf(
            "en" to
                "The CBRN situation has ended. At {time}, the CBRN warning for {area} was lifted. Please resume normal activities. [{agency}]"
        ),
        mapOf(
            "time" to StandardSlotKind.TIME,
            "area" to StandardSlotKind.AREA,
            "agency" to StandardSlotKind.AGENCY
        ),
        referenceId = "kr-civil"
    ),
    StandardMessageTemplate(
        "kr-civil-nuclear-lifted",
        "ko",
        "KR",
        "핵 상황 종료. {time}분 {area}지역 핵 경보 해제, 일상으로 복귀 바랍니다. [{agency}]",
        mapOf(
            "en" to
                "The nuclear situation has ended. At {time}, the nuclear warning for {area} was lifted. Please resume normal activities. [{agency}]"
        ),
        mapOf(
            "time" to StandardSlotKind.TIME,
            "area" to StandardSlotKind.AREA,
            "agency" to StandardSlotKind.AGENCY
        ),
        referenceId = "kr-civil"
    )
)
