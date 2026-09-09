# 0주차 MVC 과제 — 요청 흐름 관찰

대상 API: `GET /api/posts/feed`

---

## 1. Filter → Interceptor → Controller 실행 순서

### 예측한 흐름

Filter는 서블릿 컨테이너(톰캣) 레벨, Interceptor는 Spring MVC(DispatcherServlet) 레벨에서
동작하므로, 바깥쪽(Filter)이 먼저 요청을 받고 안쪽(Interceptor → Controller)으로 들어갔다가 다시
바깥쪽으로 나오는 구조일 것으로 예상

```
[FILTER] IN
  [INTERCEPTOR] PRE
    [CONTROLLER]
  [INTERCEPTOR] POST / AFTER_COMPLETION
[FILTER] OUT
```

### 실제 로그

```
[FILTER] IN  - GET /api/posts/feed
[INTERCEPTOR] PRE  - GET /api/posts/feed - handler=com.zb.blogapi.post.PostController#feed(String, int, int)
[CONTROLLER] IN  - status=PUBLISHED, page=0, size=20
[CONTROLLER] OUT - totalElements=90005
[INTERCEPTOR] POST - GET /api/posts/feed
[INTERCEPTOR] AFTER_COMPLETION - GET /api/posts/feed - status=200 - exception=null
[FILTER] OUT - GET /api/posts/feed - status=200
```

### 수정한 이해

- 다만 예측 단계에서는 몰랐던 것: `preHandle`의 `handler` 파라미터에 이미 `PostController#feed(String, int, int)`처럼
  **어떤 컨트롤러의 어떤 메서드가 호출될지에 대한 정보가 들어있다.** Filter는 이 정보를 전혀 모른다 (그냥 서블릿
  요청/응답만 다룬다). 이게 Filter와 Interceptor의 실질적인 능력 차이다.
- `postHandle`과 `afterCompletion`은 역할이 다르다: `postHandle`은 "Controller가 예외 없이 정상적으로 끝났을 때만"
  호출되고, `afterCompletion`은 성공/실패 여부와 무관하게 항상 호출
- Filter는 톰캣의 `ServletContext`에, Interceptor는 Spring의 `HandlerMapping`(ApplicationContext 관리 영역)에 등록된다. `FilterConfig`의
  `@Bean`이 실제로 톰캣에 등록되기까지, Spring Boot가 `ServletWebServerApplicationContext`를 통해 중간에서 다리를 놓아준다는 것도 확인

---

## 2. Controller 예외 vs Filter 예외 — `@RestControllerAdvice` 적용 범위

### 실험 전 이해한 개념과 그에 따른 예측

- `@RestControllerAdvice`가 예외를 잡을 수 있는 건, 그것이 `DispatcherServlet` 내부에서 관리하는
  "예외 처리기 목록"(`HandlerExceptionResolver`)에 등록되는 것이기 때문이다.
- Filter는 `DispatcherServlet`이 실행되기 이전(서블릿 컨테이너 레벨)에서 동작한다.

이 두 가지 개념을 바탕으로, "Filter에서 발생한 예외는 애초에 `DispatcherServlet` 안으로 들어가지도 못했을 테니,
`@RestControllerAdvice`의 예외 처리기 목록에 도달할 방법이 없어서 못 잡을 것"이라는 예측

### 실제 로그 및 응답 비교

**Controller에서 예외 발생** (`GET /api/posts/feed?status=ERROR`)

응답:
```json
{"timestamp":"2026-09-08T11:17:53.170512Z","status":500,"error":"Handled by @RestControllerAdvice","message":"의도적으로 발생시킨 Controller 예외"}
```

로그:
```
[FILTER] IN
[INTERCEPTOR] PRE  - handler=PostController#feed(...)
[CONTROLLER] IN - status=ERROR
[EXCEPTION_HANDLER] Controller 예외를 JSON으로 변환 - message=의도적으로 발생시킨 Controller 예외
[INTERCEPTOR] AFTER_COMPLETION - status=500 - exception=null   ← postHandle은 스킵됨
[FILTER] OUT - status=500
```

**Filter에서 예외 발생** (`curl -H "X-Force-Filter-Error: true" ...`)

응답:
```json
{"timestamp":"2026-09-08T11:19:58.140+00:00","status":500,"error":"Internal Server Error","path":"/api/posts/feed"}
```

로그:
```
[FILTER] IN
[FILTER] OUT - status=200          ← finally 블록이라 먼저 찍힘 (예외는 그 다음 던져짐)
ERROR ... Servlet.service() ... threw exception
java.lang.RuntimeException: 의도적으로 발생시킨 Filter 예외
    at com.zb.blogapi.common.filter.RequestLoggingFilter.doFilter(...)
    ...
```
→ `[EXCEPTION_HANDLER]` 로그, `[INTERCEPTOR]` 로그 모두 전혀 찍히지 않음. DispatcherServlet 자체가 실행되지 못했다.

### 수정한 이해

- Controller/Interceptor의 예외는 `DispatcherServlet.doDispatch()` 내부 흐름 안에서
  발생하므로 `HandlerExceptionResolver`가 잡을 수 있지만, Filter의 예외는 `chain.doFilter()`(=DispatcherServlet
  실행) 이전에 발생해 그 흐름 자체에 진입하지 못하고, 톰캣까지 그대로 튕겨 올라가 톰캣의 기본 에러 처리
  (`BasicErrorController`, `/error`)로 넘어간다는 걸 응답 JSON의 차이로 확인
- 예측 단계에서는 전혀 생각 못 했던 부분: Filter 쪽 `[FILTER] OUT - status=200` 로그가 예외 발생 이후에도
  먼저 찍혔다는 점. 원인은 `try { ... } finally { OUT 로그 } ` 구조에서 예외를 `chain.doFilter()` 호출 전에
  던지도록 코드를 짰기 때문에 `finally`가 먼저 실행되고, 그 뒤에 예외가 위로 전파된 것이었다. 로그 시점과
  실제 최종 응답 상태 코드(500)가 다를 수 있다는 걸 직접 확인
- Controller 예외 때는 Interceptor의 `postHandle`이 스킵되고 `afterCompletion`만 호출됐다. 그 `exception`
  파라미터가 `null`이었던 이유는, `@RestControllerAdvice`가 예외를 이미 "해결(resolve)"했기 때문에 Spring이
  더 이상 미해결 예외로 취급하지 않아서였다

---

## 3. CORS — `http://localhost:3000`만 허용

### 실험 전 이해한 개념과 그에 따른 예측

- 브라우저는 크로스 오리진 요청을 "단순 요청"과 "복잡한 요청"으로 나누고, GET/HEAD/POST + 기본 헤더만 쓰는
  "단순 요청"은 preflight(OPTIONS) 없이 바로 나간다.
- Content-Type을 `application/json`으로 지정하거나 커스텀 헤더를 추가하면 "복잡한 요청"으로 분류되어
  브라우저가 실제 요청 전에 OPTIONS로 먼저 서버의 허락을 구한다.

이 개념을 바탕으로, "우리 API는 GET + 기본 요청이라 그대로 두면 preflight가 안 뜰 것이고, preflight
발생 자체를 관찰하려면 테스트 클라이언트의 fetch 호출에 `Content-Type: application/json`을 일부러
추가해야 한다"는 예측을 세우고 테스트 페이지를 만듦

### 실제 로그 / 캡처

**허용된 출처 (`http://localhost:3000`)**

- Network 탭: `OPTIONS`(preflight) → 200, `GET` → 200, 요청 2~3건
- OPTIONS 응답 헤더:
  ```
  Access-Control-Allow-Origin: http://localhost:3000
  Access-Control-Allow-Methods: GET,POST,PUT,DELETE,OPTIONS
  Access-Control-Allow-Headers: content-type
  Access-Control-Max-Age: 3600
  ```
- 화면: "성공!" + 실제 게시글 목록 JSON 데이터 표시됨

**차단된 출처 (`http://localhost:4000`)**

- Network 탭: `feed` (preflight) → **403**, `feed` (fetch) → **CORS 오류**
- 콘솔 에러:
  ```
  Access to fetch at 'http://localhost:8080/api/posts/feed' from origin 'http://localhost:4000'
  has been blocked by CORS policy: Response to preflight request doesn't pass access control check:
  No 'Access-Control-Allow-Origin' header is present on the requested resource.
  ```
- 화면: "실패! TypeError: Failed to fetch"

### 수정한 이해

- 예측대로 `Content-Type: application/json`을 넣으니 preflight가 실제로 관찰됐다. 다만 처음 재현했을 때는
  같은 페이지에서 버튼을 두 번째 눌렀을 때 OPTIONS가 안 보여서 "왜 preflight가 안 뜨지?" 하고 헷갈렸는데,
  원인은 `Access-Control-Max-Age: 3600` 설정 때문에 브라우저가 첫 preflight 결과를 캐싱해서 재확인을
  생략한 것이었다. 이건 실험 전에는 전혀 예상하지 못했던 부분이고, 시크릿 창으로 캐시 없는 상태에서
  다시 테스트해서 preflight가 실제로 발생하는 걸 확인
- CORS는 "서버가 요청을 거부하는 것"이 아니라 "서버가 응답에 실어 보낸 `Access-Control-Allow-Origin`
  헤더를 보고 브라우저가 그 응답을 JS에게 넘겨줄지 최종 결정하는 구조"라는 걸 재확인했다. 다만 실제로는
  Spring의 CORS 처리기가 허용되지 않은 출처의 preflight 자체를 403으로 응답해버리는 것도 함께 확인
- `allowedOrigins("http://localhost:3000")`처럼 명시한 값 하나만 화이트리스트로 허용되고, 나머지는 전부
  자동으로 차단된다는 것을 4000번 포트로 직접 실패를 재현하며 확인

---

## 참고 — 커밋 목록

- `feat: Filter/Interceptor 요청 로깅 추가 - 실행 순서 관찰`
- `feat: Controller/Filter 예외처리 비교 실험 - RestControllerAdvice 적용 범위 확인`
- `feat: localhost:3000만 허용하는 CORS 설정 추가`
