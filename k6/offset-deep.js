import http from 'k6/http';
import { check, sleep } from 'k6';

// 2주차 실험: 깊은 페이지(대략 85,000번째 근처)를 OFFSET 방식으로 반복 조회.
// page=4250, size=20 -> OFFSET = 4250*20 = 85000
export const options = {
    vus: 10,
    duration: '20s',
    summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
};

const BASE_URL = 'http://localhost:8080';

export default function () {
    const res = http.get(`${BASE_URL}/api/posts/feed?status=PUBLISHED&page=4250&size=20`);
    check(res, {
        'status is 200': (r) => r.status === 200,
    });
    sleep(0.1);
}
