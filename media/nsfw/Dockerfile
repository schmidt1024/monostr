FROM python:3.14-slim
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY server.py fetch_model.py ./
# the model is part of the image: the container needs no network at run time
RUN python fetch_model.py /app/marqo-nsfw-384.onnx
RUN useradd --system --uid 10001 --no-create-home nsfw
USER nsfw
ENV NSFW_MODEL=/app/marqo-nsfw-384.onnx NSFW_LISTEN=0.0.0.0:8081 PYTHONUNBUFFERED=1
EXPOSE 8081
CMD ["python", "server.py"]
