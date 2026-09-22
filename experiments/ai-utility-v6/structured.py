"""이 실험의 고정 응답 shape만 검증하고 raw text를 복구 답변으로 사용하지 않는다."""
import json
from pathlib import Path

SCHEMA = json.loads((Path(__file__).with_name('output-schema.json')).read_text())


def validate_result(result):
    """성공 result의 구조화 응답을 반환하거나 안정적인 실패 사유를 남긴다."""
    if (not isinstance(result, dict) or result.get('type') != 'result' or
            result.get('subtype') != 'success' or result.get('is_error') is not False):
        return None, 'provider-result-failure'
    if 'structured_output' not in result:
        return None, 'missing-structured-output'
    document = result['structured_output']
    if not isinstance(document, dict) or set(document) != set(SCHEMA['required']):
        return None, 'schema-violation'
    targets, unknowns, summary = document['targets'], document['unknowns'], document['summary']
    target_schema = SCHEMA['properties']['targets']
    if (not isinstance(targets, list) or len(targets) > target_schema['maxItems'] or
            not isinstance(unknowns, list) or any(not isinstance(item, str) for item in unknowns) or
            not isinstance(summary, str)):
        return None, 'schema-violation'
    for target in targets:
        if (not isinstance(target, dict) or set(target) != set(target_schema['items']['required']) or
                any(not isinstance(value, str) for value in target.values()) or
                target['kind'] not in target_schema['items']['properties']['kind']['enum']):
            return None, 'schema-violation'
    return document, None
