# 고객 앱과 운영 콘솔의 공용 이미지입니다. APP으로 어느 쪽인지 고릅니다. 근거: ADR-011
#
# 두 앱은 **같은 이미지 정의**를 쓰되 **다른 컨테이너**로 뜹니다. 번들이 섞이면 운영 콘솔
# 코드가 고객 브라우저로 내려갑니다(DOC-14 §2).
FROM node:22-alpine AS build
WORKDIR /src

RUN corepack enable

# 워크스페이스 전체가 필요합니다. 두 앱 모두 packages/api-client를 workspace:* 로 씁니다.
COPY package.json pnpm-workspace.yaml pnpm-lock.yaml ./
COPY packages packages
COPY apps/web-customer apps/web-customer
COPY apps/web-ops apps/web-ops
COPY apps/e2e/package.json apps/e2e/package.json

RUN pnpm install --frozen-lockfile

ARG APP
RUN test -n "$APP" || (echo "APP 빌드 인자가 필요합니다 (web-customer | web-ops)" && exit 1)
# API 주소를 주입할 자리가 없습니다. 클라이언트는 언제나 자기 오리진을 쓰고, 그것이 이 배포
# 형태입니다. 다른 오리진을 가리키는 설정이 생기면 교차 오리진 호출이 되고 쿠키가 빠집니다(ADR-011).
RUN pnpm --filter "@paritypay/$APP" build

FROM nginx:1.27-alpine AS runtime
ARG APP
COPY --from=build /src/apps/${APP}/dist /usr/share/nginx/html
COPY deploy/web/nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 443
