(ns org.nfrac.cppnx.webgl-lines
  (:require [org.nfrac.cppnx.core :as cppnx :refer [remap]]
            [org.nfrac.cppnx.compile-webgl :refer [build-cppn-glsl-vals]]
            [org.nfrac.cppnx.util.algo-graph :as graph]
            [org.nfrac.cppnx.webgl-common :refer [hsv2rgb-glsl]]
            [gamma.api :as g]
            [gamma.program :as p]
            [goog.dom :as gdom]
            [goog.webgl :as ggl]))

(def start-cppn
  {:domain :lines
   :inputs #{:bias :z}
   :outputs #{:r :a :h :s :v}
   :nodes {:init :gaussian}
   :edges {:init {:z 1.0
                  :bias 1.0}
           :r {:init 1.0}
           :a {:init 1.0}
           :h {:init 1.0}
           :s {:init 1.0}
           :v {:init 1.0}}})

(def a-z (g/attribute "a_z" :float))

(def v-color (g/varying "v_color" :vec4 :highp))

(defn vertex-shader
  [cppn w-exprs]
  (let [in-exprs {:bias 1.0, :z a-z}
        out-exprs (build-cppn-glsl-vals cppn in-exprs w-exprs)
        xy (g/* (:r out-exprs)
                (g/vec2 (g/cos (g/* 3.1415 (:a out-exprs)))
                        (g/sin (g/* 3.1415 (:a out-exprs)))))
        ;; for colours, convert [-1 1] to [0 1]
        out-exprs-01 (remap #(g/+ (g/* % 0.5) 0.5) out-exprs)
        col (g/vec4 (hsv2rgb-glsl (:h out-exprs-01)
                                  (:s out-exprs-01)
                                  (:v out-exprs-01))
                    0.5)]
    {(g/gl-position) (g/vec4 xy 0 1)
     v-color col}))

(def fragment-shader
  {(g/gl-frag-color) v-color})

(defn setup
  [gl cppn]
  (let [ws (cppnx/cppn-weights cppn)
        w-uniforms (map #(g/uniform (str "u_weight" %) :float) (range (count ws)))
        w-exprs (zipmap (cppnx/edge-list cppn) w-uniforms)
        program (p/program {:vertex-shader (vertex-shader cppn w-exprs)
                            :fragment-shader fragment-shader
                            :precision {:float :highp}})
        vs  (.createShader gl ggl/VERTEX_SHADER)
        fs  (.createShader gl ggl/FRAGMENT_SHADER)
        pgm (.createProgram gl)]
    (doto gl
      (.clearColor 0 0 0 1)
      (.enable ggl/BLEND)
      (.blendFuncSeparate ggl/SRC_ALPHA ggl/ONE_MINUS_SRC_ALPHA ggl/ONE ggl/ONE_MINUS_SRC_ALPHA)
      (.lineWidth 4)
      (.shaderSource vs (-> program :vertex-shader :glsl))
      (.compileShader vs)
      (.shaderSource fs (-> program :fragment-shader :glsl))
      (.compileShader fs)
      (.attachShader pgm vs)
      (.attachShader pgm fs)
      (.linkProgram pgm))
    (when-not (.getProgramParameter gl pgm ggl/LINK_STATUS)
      (println "Shader link failed:" (.getProgramInfoLog gl pgm))
      (println "Vertex shader log:" (.getShaderInfoLog gl vs))
      (println "Fragment shader log:" (.getShaderInfoLog gl fs))
      (println "Vertex shader glsl:")
      (println (-> program :vertex-shader :glsl)))
    {:domain (:domain cppn)
     :gl gl
     :gl-program pgm
     :vertex-glsl (-> program :vertex-shader :glsl)
     :fragment-glsl (-> program :fragment-shader :glsl)
     :w-uniforms w-uniforms
     :ws ws
     :z-buffer (.createBuffer gl)}))

(defn load-weights
  [gl info w-vals]
  (doseq [[unif w-val] (map vector (:w-uniforms info) w-vals)]
    (when-let [loc (.getUniformLocation gl (:gl-program info) (:name unif))]
      (.uniform1f gl loc w-val)))
  gl)

(def z-data
  (js/Float32Array.
    (range -1.0 1.0 (/ 1 50000))))

(defn render
  [gl-info w-vals]
  (let [gl (:gl gl-info)
        pgm (:gl-program gl-info)
        zbuf (:z-buffer gl-info)]
    (doto gl
      (.clear (.-COLOR_BUFFER_BIT gl))
      (.bindBuffer ggl/ARRAY_BUFFER zbuf)
      (.bufferData ggl/ARRAY_BUFFER z-data ggl/STATIC_DRAW)
      (.enableVertexAttribArray (.getAttribLocation gl pgm (:name a-z)))
      (.vertexAttribPointer (.getAttribLocation gl pgm (:name a-z))
        1 ggl/FLOAT false 0 0)
      (.useProgram pgm)
      (load-weights gl-info w-vals)
      (.drawArrays ggl/LINE_STRIP 0 (.-length z-data)))))
