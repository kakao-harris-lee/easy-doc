import { describe, expect, it } from 'vitest'

import type { SegmentMapUnit } from '../api/types'
import {
  alignUnitMap,
  insertUnitsAfter,
  reindexUnitMap,
  spliceUnitText,
  withBaselines,
} from './unitMap'

/** 대응표 항목 하나. 기본값은 확인된(high) 1:1 대응이다. */
function unit(overrides: Partial<SegmentMapUnit> = {}): SegmentMapUnit {
  return { easy_unit_index: 0, source_unit_indexes: [0], confidence: 'high', ...overrides }
}

describe('withBaselines(되돌리기 기준선, Part C-1)', () => {
  it('단위 수만큼 길이가 맞고, 각 항목의 baseline이 그 자리의 텍스트다', () => {
    const units = [unit({ easy_unit_index: 0 }), unit({ easy_unit_index: 1 })]
    const result = withBaselines(units, '첫줄\n둘째줄')

    expect(result).toHaveLength(2)
    expect(result[0]?.baseline).toBe('첫줄')
    expect(result[1]?.baseline).toBe('둘째줄')
    // 원래 대응표 필드도 그대로 실려 있다.
    expect(result[0]?.confidence).toBe('high')
    expect(result[0]?.source_unit_indexes).toEqual([0])
  })

  it('unitMap이 text보다 짧으면 alignUnitMap으로 채운 자리도 baseline을 받는다', () => {
    const units = [unit({ easy_unit_index: 0 })]
    const result = withBaselines(units, '첫줄\n둘째줄\n셋째줄')

    expect(result).toHaveLength(3)
    expect(result[1]?.baseline).toBe('둘째줄')
    expect(result[1]?.confidence).toBe('low')
    expect(result[2]?.baseline).toBe('셋째줄')
  })
})

describe('spliceUnitText의 baseline 전파(Part C-1)', () => {
  it('첫 조각은 원래 단위의 baseline을 물려받고, 나머지 조각은 null이다', () => {
    const units = ['첫 문장']
    const map = withBaselines([unit()], '첫 문장')

    const { map: nextMap } = spliceUnitText(units, map, 0, '첫\n줄')

    expect(nextMap).toHaveLength(2)
    expect(nextMap[0]?.baseline).toBe('첫 문장')
    expect(nextMap[1]?.baseline).toBeNull()
  })

  it('개행이 없는 보통 교체는 이 함수를 타지 않지만, 있는 경우 나머지 조각 전부 null이다', () => {
    const units = ['첫 문장']
    const map = withBaselines([unit()], '첫 문장')

    const { map: nextMap } = spliceUnitText(units, map, 0, '첫\n둘\n셋')

    expect(nextMap).toHaveLength(3)
    expect(nextMap[0]?.baseline).toBe('첫 문장')
    expect(nextMap[1]?.baseline).toBeNull()
    expect(nextMap[2]?.baseline).toBeNull()
  })
})

describe('insertUnitsAfter의 baseline(Part C-1)', () => {
  it('새로 끼워 넣는 단위는 모두 baseline이 null이다', () => {
    const units = ['첫 문장']
    const map = withBaselines([unit()], '첫 문장')

    const { map: nextMap } = insertUnitsAfter(units, map, 0, '새 단위', 0)

    expect(nextMap).toHaveLength(2)
    expect(nextMap[0]?.baseline).toBe('첫 문장')
    expect(nextMap[1]?.baseline).toBeNull()
  })
})

describe('reindexUnitMap·alignUnitMap의 baseline 보존(Part C-1)', () => {
  it('reindexUnitMap은 baseline을 그대로 들고 간다', () => {
    const map = withBaselines([unit({ easy_unit_index: 0 }), unit({ easy_unit_index: 1 })], 'A\nB')

    const result = reindexUnitMap(map)

    expect(result[0]?.baseline).toBe('A')
    expect(result[1]?.baseline).toBe('B')
  })

  it('alignUnitMap은 기존 항목의 baseline을 보존하고, 채운 자리는 null이다', () => {
    const map = withBaselines([unit({ easy_unit_index: 0 })], 'A')

    const result = alignUnitMap(map, 3)

    expect(result).toHaveLength(3)
    expect(result[0]?.baseline).toBe('A')
    expect(result[1]?.baseline).toBeNull()
    expect(result[2]?.baseline).toBeNull()
  })
})
