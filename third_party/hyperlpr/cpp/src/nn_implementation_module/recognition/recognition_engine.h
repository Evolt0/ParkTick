// Modified for ParkTick: optional ordinary-plate character count and bounded tensor decoder.
//
// Created by tunm on 2023/2/6.
//
#pragma once
#ifndef ZEPHYRLPR_RECOGNITION_ENGINE_H
#define ZEPHYRLPR_RECOGNITION_ENGINE_H

#include "inference_helper_module/inference_helper.h"
#include "configuration.h"
#include "recognition_commom.h"
#include "basic_types.h"
#include "opencv2/opencv.hpp"

namespace hyper {

class RecognitionEngine {
public:

    RecognitionEngine(const RecognitionEngine &) = delete;

    RecognitionEngine &operator=(const RecognitionEngine &) = delete;

    explicit RecognitionEngine();

    ~RecognitionEngine();

    int32_t Initialize(const std::string& model_filename, cv::Size input_size = REC_INPUT_SIZE, int threads = 1,
                       float confidence_threshold = 0.5, bool use_half = false);

    int32_t Inference(const cv::Mat &bgr_pad, TextLine &line, int expected_char_count = 0);

    const cv::Size &getMInputImageSize() const;

    float getMConfidenceThreshold() const;

private:

    int32_t decode(const float* tensor, int element_count, TextLine &line, int expected_char_count);

private:
    cv::Size m_input_image_size_{};                             // 输入图像宽高

    float m_confidence_threshold_{};                            // 置信度阈值

    std::unique_ptr<InferenceHelper> m_nn_infer_;               // 推理模块

    std::vector<InputTensorInfo> m_input_tensor_info_list_;

    std::vector<OutputTensorInfo> m_output_tensor_info_list_;


};

} // namespace

#endif //ZEPHYRLPR_RECOGNITION_ENGINE_H
