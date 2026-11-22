# better-sqlite3 ships prebuilt binaries for glibc on x64 and arm64, so no compiler is needed and install scripts
# stay off (--ignore-scripts).
FROM node:26.13-trixie-slim AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --ignore-scripts
COPY tsconfig.json tsconfig.build.json ./
COPY src ./src
RUN npm run build

FROM node:26.13-trixie-slim
ENV NODE_ENV=production WRTHUB_DB_PATH=/data/wrthub.db
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --ignore-scripts --omit=dev && npm cache clean --force
COPY --from=build /app/dist ./dist
COPY drizzle ./drizzle
RUN mkdir /data && chown node:node /data
USER node
VOLUME /data
EXPOSE 8080
CMD ["node", "dist/main.js"]
