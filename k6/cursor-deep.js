import http from 'k6/http';
import { check, sleep } from 'k6';

// 2주차 실험: OFFSET 실험과 같은 "깊은 위치"(대략 85,000번째)를 커서 방식으로 반복 조회.
// 커서 값은 사전에 SQL로 직접 확인한 실제 데이터 지점이다:
//   SELECT created_at, id FROM posts WHERE status='PUBLISHED'
//   ORDER BY created_at DESC, id DESC LIMIT 1 OFFSET 85000;
export const options = {
    vus: 10,
    duration: '20s',
    summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
};

const BASE_URL = 'http://localhost:8080';

export default function () {
    const res = http.get(
        `${BASE_URL}/api/posts/feed-cursor?cursorCreatedAt=2024-10-20T12:41:54&cursorId=7590&size=20`
    );
    check(res, {
        'status is 200': (r) => r.status === 200,
    });
    sleep(0.1);
}
