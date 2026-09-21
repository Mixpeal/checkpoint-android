#!/usr/bin/env python3
"""
Build the single ONNX file the Android app loads.

The app hands the session a raw String. That is deliberate: in the web prototype the
tokenizer was a separate 17 MB JSON download, it once arrived truncated, and the failure
surfaced three rounds later as an unrelated parse error. Compiling the tokenizer into the
graph removes tokenizer parity as a category of bug instead of managing it.

    string  ->  sentencepiece  ->  e5-small  ->  mean pool  ->  [1, 384]

L2 normalisation stays in Kotlin, matching sentence-transformers' Normalize module.

Run:  tools/build_model.py
"""
import pathlib
import sys

import numpy as np
import onnx
from onnx import TensorProto, helper
from onnxruntime_extensions import gen_processing_models
from transformers import XLMRobertaTokenizer

WEB = pathlib.Path("/Users/mixpeal/General/checkpoint/models/Xenova/multilingual-e5-small")
OUT = pathlib.Path(__file__).resolve().parent.parent / "build-assets" / "e5-small-tokenized.onnx"
PREFIX = "query: "                      # MUST match Embedder.QUERY_PREFIX and training
MAX_TOKENS = 512                        # e5-small has 514 position embeddings


def adapter_graph(opset: int, token_dtype: int) -> onnx.ModelProto:
    """
    The sentencepiece op emits one flat, ragged token list. The encoder wants a matrix.

    The app embeds exactly one string per call, so there is a single instance and no
    padding: reshaping to [1, N] is the whole conversion. Truncation is the only real
    work, and it exists because a long paste would otherwise run past the model's
    position embeddings.
    """
    nodes = [
        helper.make_node("Reshape", ["tok_in", "row"], ["full"]),
        helper.make_node("Slice", ["full", "zero", "cap", "axis1"], ["capped"]),
        # The tokenizer op emits int32; the encoder's input_ids are int64.
        helper.make_node("Cast", ["capped"], ["ids"], to=TensorProto.INT64),
        helper.make_node("Shape", ["ids"], ["shape"]),
        helper.make_node("ConstantOfShape", ["shape"], ["types"],
                         value=helper.make_tensor("t0", TensorProto.INT64, [1], [0])),
        helper.make_node("ConstantOfShape", ["shape"], ["mask"],
                         value=helper.make_tensor("t1", TensorProto.INT64, [1], [1])),
    ]
    graph = helper.make_graph(
        nodes,
        "ragged_to_matrix",
        inputs=[helper.make_tensor_value_info("tok_in", token_dtype, ["N"])],
        outputs=[
            helper.make_tensor_value_info("ids", TensorProto.INT64, [1, "S"]),
            helper.make_tensor_value_info("mask", TensorProto.INT64, [1, "S"]),
            helper.make_tensor_value_info("types", TensorProto.INT64, [1, "S"]),
        ],
        initializer=[
            helper.make_tensor("row", TensorProto.INT64, [2], [1, -1]),
            helper.make_tensor("zero", TensorProto.INT64, [1], [0]),
            helper.make_tensor("cap", TensorProto.INT64, [1], [MAX_TOKENS]),
            helper.make_tensor("axis1", TensorProto.INT64, [1], [1]),
        ],
    )
    return helper.make_model(graph, opset_imports=[helper.make_opsetid("", opset)])


def pooling_graph(hidden: int, opset: int) -> onnx.ModelProto:
    """
    Mean over the token axis.

    sentence-transformers weights this by the attention mask. Here every token is real,
    because one string in means no padding, so the mask is all ones and the weighted mean
    is the plain mean. Carrying a mask input would be arithmetic that cannot change.
    """
    # ReduceMean moved axes from an attribute to an input in opset 18.
    initializer = []
    if opset >= 18:
        node = helper.make_node("ReduceMean", ["pool_hidden", "pool_axis"], ["sentence_embedding"],
                                keepdims=0)
        initializer.append(helper.make_tensor("pool_axis", TensorProto.INT64, [1], [1]))
    else:
        node = helper.make_node("ReduceMean", ["pool_hidden"], ["sentence_embedding"],
                                axes=[1], keepdims=0)

    graph = helper.make_graph(
        [node],
        "mean_pool",
        inputs=[helper.make_tensor_value_info("pool_hidden", TensorProto.FLOAT, [1, "S", hidden])],
        outputs=[helper.make_tensor_value_info("sentence_embedding", TensorProto.FLOAT, [1, hidden])],
        initializer=initializer,
    )
    return helper.make_model(graph, opset_imports=[helper.make_opsetid("", opset)])


def std_opset(model: onnx.ModelProto) -> int:
    return next(o.version for o in model.opset_import if o.domain in ("", "ai.onnx"))


def unify_ir(models: list) -> None:
    """
    merge_models refuses models built against different IR versions. Everything here uses
    long-standing operators, so the lowest IR among the inputs describes them all.
    """
    floor = min(m.ir_version for m in models)
    for m in models:
        m.ir_version = floor


def align(pre: onnx.ModelProto, body: onnx.ModelProto):
    """
    merge_models needs one standard opset. Try lifting the encoder first. It is quantized,
    and the version converter does not always handle quantized operators, so fall back to
    lowering the tokenizer graph and say which path was taken.
    """
    lo, hi = std_opset(body), std_opset(pre)
    if lo == hi:
        return pre, body, lo
    try:
        return pre, onnx.version_converter.convert_version(body, hi), hi
    except Exception as err:
        print(f"align     encoder {lo}->{hi} failed ({type(err).__name__}), lowering tokenizer instead")
        return onnx.version_converter.convert_version(pre, lo), body, lo


def main() -> int:
    body = onnx.load(str(WEB / "onnx" / "model_quantized.onnx"))
    body_in = [i.name for i in body.graph.input]
    body_out = [o.name for o in body.graph.output]
    print(f"body      opset={std_opset(body)} in={body_in} out={body_out}")

    # The converter reads the raw sentencepiece model, so the slow tokenizer is required.
    # Loading from tokenizer.json alone leaves it without a vocab_file.
    tok = XLMRobertaTokenizer(vocab_file=str(WEB / "sentencepiece.bpe.model"))
    pre, _ = gen_processing_models(tok, pre_kwargs={})
    print(f"tokenizer opset={std_opset(pre)} in={[i.name for i in pre.graph.input]} "
          f"out={[o.name for o in pre.graph.output]}")

    token_dtype = next(o.type.tensor_type.elem_type for o in pre.graph.output if o.name == "tokens")
    print(f"tokens    dtype={onnx.TensorProto.DataType.Name(token_dtype)}")

    pre, body, opset = align(pre, body)
    adapter = adapter_graph(opset, token_dtype)
    pool = pooling_graph(body.graph.output[0].type.tensor_type.shape.dim[-1].dim_value or 384, opset)
    unify_ir([pre, body, adapter, pool])
    print(f"align     merged at opset {opset}, ir {pre.ir_version}")

    step1 = onnx.compose.merge_models(
        pre, adapter,
        io_map=[("tokens", "tok_in")],
        outputs=["ids", "mask", "types"],
    )
    step2 = onnx.compose.merge_models(
        step1, body,
        io_map=[("ids", "input_ids"), ("mask", "attention_mask"), ("types", "token_type_ids")],
        outputs=body_out,
    )
    combined = onnx.compose.merge_models(
        step2, pool,
        io_map=[(body_out[0], "pool_hidden")],
        outputs=["sentence_embedding"],
    )
    onnx.checker.check_model(combined, full_check=False)

    OUT.parent.mkdir(parents=True, exist_ok=True)
    onnx.save(combined, str(OUT))
    print(f"wrote     {OUT}  {OUT.stat().st_size:,} bytes")
    return verify(str(OUT))


def verify(path: str) -> int:
    """The graph is worthless if it disagrees with the encoder the heads were fitted on."""
    import onnxruntime as ort
    from onnxruntime_extensions import get_library_path
    from sentence_transformers import SentenceTransformer

    opts = ort.SessionOptions()
    opts.register_custom_ops_library(get_library_path())
    sess = ort.InferenceSession(path, opts, providers=["CPUExecutionProvider"])
    name = sess.get_inputs()[0].name
    print(f"session   input={name} output={[o.name for o in sess.get_outputs()]}")

    probes = [
        "police stopped me and asked for 50k",
        "dem talk say my particulars no complete",
        "can they search my boot without telling me why",
        "wetin be the fine for driving without licence",
        "they took my key and said i must follow them to the station",
    ]
    ref = SentenceTransformer("intfloat/multilingual-e5-small")

    worst = 1.0
    for text in probes:
        got = sess.run(None, {name: np.array([PREFIX + text])})[0][0]
        got = got / max(float(np.linalg.norm(got)), 1e-9)
        want = ref.encode(PREFIX + text, normalize_embeddings=True)
        cos = float(np.dot(got, want))
        worst = min(worst, cos)
        print(f"cos={cos:.4f}  {text}")

    print(f"\nworst cosine vs sentence-transformers: {worst:.4f}")
    if worst < 0.99:
        print("BELOW 0.99. The heads were fitted on the reference encoder. Do not ship this.")
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
