import http from 'k6/http';
import { check, sleep } from 'k6';

// 2주차 N+1 실험 - N+1이 발생하는 버전.
// posts 1 + count 1 + author(user) N = 쿼리 22개 (size=20 기준)
export const options = {
    vus: 10,
    duration: '20s',
    summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
};

const BASE_URL = 'http://localhost:8080';

export default function () {
    const res = http.get(`${BASE_URL}/api/posts/feed-with-author?status=PUBLISHED&page=0&size=20`);
    check(res, {
        'status is 200': (r) => r.status === 200,
    });
    sleep(0.1);
}
