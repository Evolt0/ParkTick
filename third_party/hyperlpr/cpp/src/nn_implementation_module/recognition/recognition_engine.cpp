// Modified for ParkTick: bounded CTC decoding, normalized confidence, and invalid-output handling.
//
// Created by tunm on 2023/2/6.
//

#include "recognition_engine.h"
#include <utility>
#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <limits>
#include "plate_recognition_tokenize.h"

#define INPUT_NAME "data"

namespace hyper {


RecognitionEngine::RecognitionEngine() = default;


int32_t RecognitionEngine::Initialize(const std::string& model_filename, cv::Size input_size, int threads, float confidence_threshold,
                                      bool use_half) {

    m_input_image_size_ = input_size;
    m_confidence_threshold_ = confidence_threshold;


    /* Set output tensor info */
    m_output_tensor_info_list_.clear();
    m_output_tensor_info_list_.push_back(OutputTensorInfo("output", TensorInfo::kTensorTypeFp32));


    m_nn_infer_.reset(InferenceHelper::Create(InferenceHelper::kMnn));

    if (m_nn_infer_->SetNumThreads(threads) != InferenceHelper::kRetOk) {
        m_nn_infer_.reset();
        return InferenceHelper::kRetErr;
    }

    if (m_nn_infer_->Initialize(model_filename, m_input_tensor_info_list_, m_output_tensor_info_list_) != InferenceHelper::kRetOk) {
        m_nn_infer_.reset();
        return InferenceHelper::kRetErr;
    }

    m_input_tensor_info_list_.clear();

    InputTensorInfo input_tensor_info(INPUT_NAME, TensorInfo::kTensorTypeFp32, true);

    input_tensor_info.tensor_dims =  { 1, 3, m_input_image_size_.height, m_input_image_size_.width };
    input_tensor_info.data_type = InputTensorInfo::kDataTypeImage;

    input_tensor_info.normalize.mean[0] = 127.5f;
    input_tensor_info.normalize.mean[1] = 127.5f;
    input_tensor_info.normalize.mean[2] = 127.5f;
    input_tensor_info.normalize.norm[0] = 0.007843137255f;
    input_tensor_info.normalize.norm[1] = 0.007843137255f;
    input_tensor_info.normalize.norm[2] = 0.007843137255f;

    m_input_tensor_info_list_.push_back(input_tensor_info);

    return InferenceHelper::kRetOk;
}

int32_t RecognitionEngine::Inference(const cv::Mat &bgr_pad, TextLine &line, int expected_char_count) {
    line = TextLine{};
    if (!m_nn_infer_ || m_input_tensor_info_list_.empty() || bgr_pad.empty()) {
        return InferenceHelper::kRetErr;
    }
    InputTensorInfo& input_tensor_info = m_input_tensor_info_list_[0];

    input_tensor_info.data = bgr_pad.data;
    input_tensor_info.data_type = InputTensorInfo::kDataTypeImage;
    input_tensor_info.image_info.width = bgr_pad.cols;
    input_tensor_info.image_info.height = bgr_pad.rows;
    input_tensor_info.image_info.channel = bgr_pad.channels();
    input_tensor_info.image_info.crop_x = 0;
    input_tensor_info.image_info.crop_y = 0;
    input_tensor_info.image_info.crop_width = bgr_pad.cols;
    input_tensor_info.image_info.crop_height = bgr_pad.rows;
    input_tensor_info.image_info.is_bgr = true;
    input_tensor_info.image_info.swap_color = false;

    if (m_nn_infer_->PreProcess(m_input_tensor_info_list_) != InferenceHelper::kRetOk) {
        return InferenceHelper::kRetErr;
    }
    if (m_nn_infer_->Process(m_output_tensor_info_list_) != InferenceHelper::kRetOk) {
        return InferenceHelper::kRetErr;
    }

    auto& output = m_output_tensor_info_list_[0];
    return decode(output.GetDataAsFloat(), output.GetElementNum(), line, expected_char_count);
}

const cv::Size &RecognitionEngine::getMInputImageSize() const {
    return m_input_image_size_;
}


float RecognitionEngine::getMConfidenceThreshold() const {
    return m_confidence_threshold_;
}


namespace {
constexpr int kBeamWidth = 32;
constexpr double kLogZero = -std::numeric_limits<double>::infinity();

double logAdd(double a, double b) {
    if (a == kLogZero) return b;
    if (b == kLogZero) return a;
    if (a < b) std::swap(a, b);
    return a + std::log1p(std::exp(b - a));
}

struct Prefix {
    std::array<uint8_t, REC_MAX_CHAR_NUM> labels{};
    int length = 0;
    double blank = kLogZero;
    double nonblank = kLogZero;
    double total = kLogZero;
};

bool prefixLess(const Prefix& a, const Prefix& b) {
    return std::lexicographical_compare(a.labels.begin(), a.labels.begin() + a.length,
                                        b.labels.begin(), b.labels.begin() + b.length);
}
}

int32_t RecognitionEngine::decode(const float* tensor, int element_count, TextLine &line,
                                  int expected_char_count) {
    line = TextLine{};
    if (!tensor || element_count != REC_MAX_CHAR_NUM * REC_CHAR_CLASS_NUM ||
        TOKENIZE.empty() || TOKENIZE.size() > REC_CHAR_CLASS_NUM || expected_char_count < 0 ||
        expected_char_count > REC_MAX_CHAR_NUM) {
        return InferenceHelper::kRetErr;
    }

    std::array<std::array<double, REC_CHAR_CLASS_NUM>, REC_MAX_CHAR_NUM> log_probs{};
    // MNN's reduced-precision softmax may slightly exceed one. Normalize
    // finite nonnegative row scores rather than assuming exact unit sums.
    std::array<double, REC_MAX_CHAR_NUM> row_sums{};
    for (int t = 0; t < REC_MAX_CHAR_NUM; ++t) {
        double sum = 0.0;
        for (int c = 0; c < REC_CHAR_CLASS_NUM; ++c) {
            const float value = tensor[t * REC_CHAR_CLASS_NUM + c];
            if (!std::isfinite(value) || value < 0.0f) {
                return InferenceHelper::kRetErr;
            }
            sum += value;
        }
        if (sum <= 0.0) return InferenceHelper::kRetErr;
        row_sums[t] = sum;
        for (int c = 0; c < REC_CHAR_CLASS_NUM; ++c) {
            const float value = tensor[t * REC_CHAR_CLASS_NUM + c];
            log_probs[t][c] = value > 0.0f ? std::log(value / sum) : kLogZero;
        }
    }

    // Sum CTC alignments per collapsed prefix instead of choosing one greedy path.
    // Blank/nonblank endings must remain separate: adjacent equal characters
    // extend the text only when a blank separates them.
    std::vector<Prefix> beam;
    std::vector<Prefix> candidates;
    beam.reserve(kBeamWidth);
    candidates.reserve(kBeamWidth * (REC_CHAR_CLASS_NUM + 1));
    beam.emplace_back();
    beam[0].blank = 0.0;
    beam[0].total = 0.0;
    const int max_length = expected_char_count > 0 ? expected_char_count : REC_MAX_CHAR_NUM;
    for (int t = 0; t < REC_MAX_CHAR_NUM; ++t) {
        candidates.clear();
        for (const auto& prefix : beam) {
            const double total = prefix.total;
            Prefix stay = prefix;
            stay.blank = total + log_probs[t][0];
            stay.nonblank = prefix.length > 0
                ? prefix.nonblank + log_probs[t][prefix.labels[prefix.length - 1]] : kLogZero;
            candidates.push_back(stay);
            if (prefix.length >= max_length) continue;
            // Upstream's tensor has 78 classes but its published dictionary
            // defines only indices 0..76. Never emit an unmapped label.
            for (int c = 1; c < static_cast<int>(TOKENIZE.size()); ++c) {
                const bool repeated = prefix.length > 0 && prefix.labels[prefix.length - 1] == c;
                const double source = repeated ? prefix.blank : total;
                if (source == kLogZero || log_probs[t][c] == kLogZero) continue;
                Prefix next = prefix;
                next.labels[next.length++] = static_cast<uint8_t>(c);
                next.blank = kLogZero;
                next.nonblank = source + log_probs[t][c];
                candidates.push_back(next);
            }
        }
        std::sort(candidates.begin(), candidates.end(), prefixLess);
        size_t count = 0;
        for (size_t i = 0; i < candidates.size(); ++i) {
            const auto& candidate = candidates[i];
            if ((candidate.blank == kLogZero && candidate.nonblank == kLogZero) ||
                (expected_char_count > 0 &&
                 candidate.length + REC_MAX_CHAR_NUM - t - 1 < expected_char_count)) {
                continue;
            }
            if (count > 0 && !prefixLess(candidates[count - 1], candidate)) {
                auto& merged = candidates[count - 1];
                merged.blank = logAdd(merged.blank, candidate.blank);
                merged.nonblank = logAdd(merged.nonblank, candidate.nonblank);
            } else {
                candidates[count++] = candidate;
            }
        }
        candidates.resize(count);
        for (auto& candidate : candidates) {
            candidate.total = logAdd(candidate.blank, candidate.nonblank);
        }
        const size_t keep = std::min(count, static_cast<size_t>(kBeamWidth));
        std::partial_sort(candidates.begin(), candidates.begin() + keep, candidates.end(),
                          [](const Prefix& a, const Prefix& b) {
                              if (a.total != b.total) return a.total > b.total;
                              return prefixLess(a, b);
                          });
        beam.assign(candidates.begin(), candidates.begin() + keep);
        if (beam.empty()) return InferenceHelper::kRetErr;
    }

    const auto& best = beam.front();
    if (best.length == 0) return InferenceHelper::kRetErr;
    // Recover the most likely alignment of the chosen text for character
    // confidence. Keep actual model evidence, not beam/length-prior confidence.
    constexpr int kMaxStates = 2 * REC_MAX_CHAR_NUM + 1;
    const int states = 2 * best.length + 1;
    std::array<double, kMaxStates> previous;
    std::array<double, kMaxStates> current;
    std::array<std::array<uint8_t, kMaxStates>, REC_MAX_CHAR_NUM> back{};
    previous.fill(kLogZero);
    previous[0] = log_probs[0][0];
    previous[1] = log_probs[0][best.labels[0]];
    for (int t = 1; t < REC_MAX_CHAR_NUM; ++t) {
        current.fill(kLogZero);
        for (int s = 0; s < states; ++s) {
            const int label = s % 2 == 0 ? 0 : best.labels[s / 2];
            int source = s;
            if (s > 0 && previous[s - 1] > previous[source]) source = s - 1;
            if (s > 1 && label != 0 && label != best.labels[s / 2 - 1] &&
                previous[s - 2] > previous[source]) source = s - 2;
            current[s] = previous[source] + log_probs[t][label];
            back[t][s] = static_cast<uint8_t>(source);
        }
        previous = current;
    }
    int state = previous[states - 1] > previous[states - 2] ? states - 1 : states - 2;
    if (previous[state] == kLogZero) return InferenceHelper::kRetErr;
    line.char_scores.assign(best.length, 0.0f);
    for (int t = REC_MAX_CHAR_NUM - 1; t >= 0; --t) {
        if (state % 2 != 0) {
            const int index = state / 2;
            line.char_scores[index] = std::max(line.char_scores[index],
                static_cast<float>(tensor[t * REC_CHAR_CLASS_NUM + best.labels[index]] / row_sums[t]));
        }
        if (t > 0) state = back[t][state];
    }
    float total = 0.0f;
    line.char_index.reserve(best.length);
    for (int i = 0; i < best.length; ++i) {
        line.char_index.push_back(best.labels[i]);
        line.code += TOKENIZE[best.labels[i]];
        total += line.char_scores[i];
    }
    line.average_score = total / best.length;
    return InferenceHelper::kRetOk;
}

RecognitionEngine::~RecognitionEngine() {
    if (!m_nn_infer_) {
        LOGD("Inference helper is not created\n");
        return;
    }
    m_nn_infer_->Finalize();
    m_nn_infer_.reset();
}

} // namespace