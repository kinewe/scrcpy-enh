#include "image_convert.h"

#include <stdlib.h>
#include <string.h>

#include <libavcodec/avcodec.h>
#include <libavutil/avutil.h>
#include <libavutil/frame.h>
#include <libswscale/swscale.h>

#include "util/log.h"

// JPEG quality (1-100)
#define SC_JPEG_QUALITY 95

bool
sc_image_bmp_to_jpeg(const uint8_t *bmp_data, size_t bmp_size,
                     uint8_t **out_data, size_t *out_size) {
    if (!bmp_data || bmp_size == 0 || bmp_size > INT_MAX) {
        return false;
    }

    // Decode the BMP
    const AVCodec *decoder = avcodec_find_decoder(AV_CODEC_ID_BMP);
    if (!decoder) {
        LOGE("Could not find BMP decoder");
        return false;
    }

    AVCodecContext *dec_ctx = avcodec_alloc_context3(decoder);
    if (!dec_ctx) {
        return false;
    }

    bool ok = false;
    AVFrame *dec_frame = NULL;
    AVPacket *pkt = NULL;
    AVFrame *enc_frame = NULL;
    AVCodecContext *enc_ctx = NULL;
    struct SwsContext *sws = NULL;
    AVPacket *out_pkt = NULL;

    if (avcodec_open2(dec_ctx, decoder, NULL) < 0) {
        LOGE("Could not open BMP decoder");
        goto end;
    }

    pkt = av_packet_alloc();
    if (!pkt) {
        goto end;
    }
    // The BMP decoder processes the packet synchronously in avcodec_send_packet
    // and does not keep a reference to the data afterwards (pkt->buf is NULL).
    pkt->data = (uint8_t *) bmp_data;
    pkt->size = (int) bmp_size;

    dec_frame = av_frame_alloc();
    if (!dec_frame) {
        goto end;
    }

    if (avcodec_send_packet(dec_ctx, pkt) < 0) {
        LOGE("Could not send BMP data to decoder");
        goto end;
    }
    if (avcodec_receive_frame(dec_ctx, dec_frame) < 0) {
        LOGE("Could not decode BMP data");
        goto end;
    }

    // Encode the JPEG
    const AVCodec *encoder = avcodec_find_encoder(AV_CODEC_ID_MJPEG);
    if (!encoder) {
        LOGE("Could not find MJPEG encoder");
        goto end;
    }

    enc_ctx = avcodec_alloc_context3(encoder);
    if (!enc_ctx) {
        goto end;
    }
    enc_ctx->width = dec_frame->width;
    enc_ctx->height = dec_frame->height;
    enc_ctx->time_base = (AVRational) {1, 25};
    enc_ctx->pix_fmt = AV_PIX_FMT_YUVJ420P;
    enc_ctx->flags |= AV_CODEC_FLAG_QSCALE;
    enc_ctx->global_quality = SC_JPEG_QUALITY * FF_QUALITY_SCALE;

    if (avcodec_open2(enc_ctx, encoder, NULL) < 0) {
        LOGE("Could not open MJPEG encoder");
        goto end;
    }

    enc_frame = av_frame_alloc();
    if (!enc_frame) {
        goto end;
    }
    enc_frame->format = AV_PIX_FMT_YUVJ420P;
    enc_frame->width = dec_frame->width;
    enc_frame->height = dec_frame->height;
    if (av_frame_get_buffer(enc_frame, 32) < 0) {
        LOGE("Could not allocate frame buffer for JPEG encoding");
        goto end;
    }

    sws = sws_getContext(dec_frame->width, dec_frame->height, dec_frame->format,
                         enc_frame->width, enc_frame->height,
                         AV_PIX_FMT_YUVJ420P, SWS_BILINEAR, NULL, NULL, NULL);
    if (!sws) {
        LOGE("Could not create pixel format converter");
        goto end;
    }
    sws_scale(sws, (const uint8_t *const *) dec_frame->data,
              dec_frame->linesize, 0, dec_frame->height,
              enc_frame->data, enc_frame->linesize);

    if (avcodec_send_frame(enc_ctx, enc_frame) < 0) {
        LOGE("Could not send frame to JPEG encoder");
        goto end;
    }

    out_pkt = av_packet_alloc();
    if (!out_pkt) {
        goto end;
    }
    if (avcodec_receive_packet(enc_ctx, out_pkt) < 0) {
        LOGE("Could not encode JPEG data");
        goto end;
    }

    uint8_t *jpeg_data = malloc(out_pkt->size);
    if (!jpeg_data) {
        goto end;
    }
    memcpy(jpeg_data, out_pkt->data, out_pkt->size);
    *out_data = jpeg_data;
    *out_size = out_pkt->size;
    ok = true;

end:
    if (out_pkt) {
        av_packet_free(&out_pkt);
    }
    if (sws) {
        sws_freeContext(sws);
    }
    if (enc_frame) {
        av_frame_free(&enc_frame);
    }
    if (enc_ctx) {
        avcodec_free_context(&enc_ctx);
    }
    if (dec_frame) {
        av_frame_free(&dec_frame);
    }
    if (pkt) {
        av_packet_free(&pkt);
    }
    avcodec_free_context(&dec_ctx);
    return ok;
}
