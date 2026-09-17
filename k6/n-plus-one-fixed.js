import http from 'k6/http';
import { check, sleep } from 'k6';

// 2주차 N+1 실험 - JOIN FETCH로 해결한 버전.
// posts+author 조인 1개 쿼리 (Page 타입이라 count 쿼리는 여전히 1개 추가로 나감)
export const options = {
    vus: 10,
    duration: '20s',
    summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
};

const BASE_URL = 'http://localhost:8080';

export default function () {
    const res = http.get(`${BASE_URL}/api/posts/feed-with-author-fixed?status=PUBLISHED&page=0&size=20`);
    check(res, {
        'status is 200': (r) => r.status === 200,
    });
    sleep(0.1);
}
