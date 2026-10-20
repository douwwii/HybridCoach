FROM docker.io/library/python:3.13-slim
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt && useradd --uid 10001 --create-home bridge && mkdir /data && chown bridge:bridge /data
COPY bridge ./bridge
USER bridge
EXPOSE 8000
CMD ["uvicorn", "bridge.app:configured_app", "--factory", "--host", "0.0.0.0", "--port", "8000", "--workers", "1", "--no-access-log"]
