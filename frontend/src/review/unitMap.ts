import type { SegmentConfidence, SegmentMapUnit } from '../api/types'

/**
 * 결과 단위 목록·대응표(`unitMap`)를 함께 다루는 자리.
 *
 * `unitMap.length === units.length`는 `SegmentedResultEditor`가 지키는 구조적
 * 불변식이다(원 컴포넌트의 CRITICAL 리뷰). 이 불변식을 지켜야 하는 자리가 그
 * 컴포넌트 하나만이 아니게 됐다 — `ReviewEditor`의 재변환 채택(「바꾸기」·「이 위치에
 * 넣기」)도 `\n`이 섞인 후보 텍스트로 단위 수를 늘릴 수 있어(P0-4 S5 리뷰 HIGH 1) 같은
 * 규칙이 필요하다. 그래서 이 로직을 여기 한 곳에 모은다 — 두 곳에서 각자 다시 짜면
 * 불변식이 어긋나는 자리가 둘로 늘어난다.
 */

/** `easy_unit_index`를 배열 위치와 다시 맞춘다. 분할·병합·재변환 채택 뒤 항상 이 함수를 거친다. */
export function reindexUnitMap(units: SegmentMapUnit[]): SegmentMapUnit[] {
  return units.map((unit, index) => ({ ...unit, easy_unit_index: index }))
}

/**
 * `unitMap`이 `unitCount`와 길이가 다르면 안전하게 맞춘다.
 *
 * 서버 응답이 아직 오지 않았거나 예상 밖의 경합이 남긴 낡은 지도를 만났을 때만 자리를
 * 채운다 — 어긋난 옛 항목을 엉뚱한 단위에 잘못 붙이지 않고 「대응 확인 불가」로 둔다.
 */
export function alignUnitMap(map: SegmentMapUnit[], unitCount: number): SegmentMapUnit[] {
  if (map.length === unitCount) {
    return map
  }
  return Array.from({ length: unitCount }, (_, index) => {
    const existing = map[index]
    return (
      existing ?? {
        easy_unit_index: index,
        source_unit_indexes: [],
        confidence: 'low' as SegmentConfidence,
      }
    )
  }).map((unit, index) => ({ ...unit, easy_unit_index: index }))
}

/** 단위 배열과 그와 길이가 같은 대응표를 함께 돌려준다. */
export interface UnitSplice {
  units: string[]
  map: SegmentMapUnit[]
}

/**
 * 기존 단위 하나(`index`)를 새 텍스트로 갈아 끼운다.
 *
 * 텍스트에 `\n`이 있으면(Shift+Enter·붙여넣기·드롭·재변환 채택 모두 해당) 그 자리에서
 * 나뉘는 것으로 보고 지도도 함께 다시 짠다 — 첫 조각만 원래 단위의 대응(confidence·
 * source_unit_indexes)을 물려받고, 새로 생긴 나머지 조각은 무엇에 대응하는지 알 수
 * 없으므로 `low`·빈 배열로 안전하게 둔다.
 *
 * `SegmentedResultEditor.handleUnitTextChange`(타이핑·붙여넣기)와 `ReviewEditor`의
 * 재변환 채택(「바꾸기」·「이 위치에 넣기」캐럿 삽입)이 이 함수 하나를 공유한다.
 */
export function spliceUnitText(
  units: readonly string[],
  map: SegmentMapUnit[],
  index: number,
  text: string,
): UnitSplice {
  const currentMap = alignUnitMap(map, units.length)
  const parts = text.split('\n')
  const nextUnits = [...units.slice(0, index), ...parts, ...units.slice(index + 1)]
  const original = currentMap[index]
  const inserted: SegmentMapUnit[] = parts.map((_, partIndex) =>
    partIndex === 0
      ? {
          easy_unit_index: 0,
          source_unit_indexes: [...(original?.source_unit_indexes ?? [])],
          confidence: original?.confidence ?? ('low' as SegmentConfidence),
        }
      : { easy_unit_index: 0, source_unit_indexes: [], confidence: 'low' as SegmentConfidence },
  )
  const nextMap = reindexUnitMap([
    ...currentMap.slice(0, index),
    ...inserted,
    ...currentMap.slice(index + 1),
  ])
  return { units: nextUnits, map: nextMap }
}

/**
 * 새 단위(들)를 `anchorIndex` 바로 뒤에 붙인다(재변환 「이 위치에 넣기」가 캐럿을 모를
 * 때, 계획 §6 S5).
 *
 * 텍스트에 `\n`이 있으면 여러 단위로 나뉘고, 전부 `sourceUnitIndex`에 `high`로
 * 대응시킨다 — `spliceUnitText`와 달리 이 새 단위들은 **어디서 왔는지 안다**(이
 * 재변환이 바로 그 원본 단위 하나에서 나왔다). 그래서 나뉜 조각 모두가 같은 대응을
 * 받는다 — 타이핑·붙여넣기로 늘어난 자리처럼 대응을 알 수 없는 경우와는 다르다.
 */
export function insertUnitsAfter(
  units: readonly string[],
  map: SegmentMapUnit[],
  anchorIndex: number,
  text: string,
  sourceUnitIndex: number,
): UnitSplice {
  const currentMap = alignUnitMap(map, units.length)
  const insertAt = Math.min(anchorIndex + 1, units.length)
  const parts = text.split('\n')
  const inserted: SegmentMapUnit[] = parts.map(() => ({
    easy_unit_index: 0,
    source_unit_indexes: [sourceUnitIndex],
    confidence: 'high' as SegmentConfidence,
  }))
  const nextUnits = [...units.slice(0, insertAt), ...parts, ...units.slice(insertAt)]
  const nextMap = reindexUnitMap([
    ...currentMap.slice(0, insertAt),
    ...inserted,
    ...currentMap.slice(insertAt),
  ])
  return { units: nextUnits, map: nextMap }
}
