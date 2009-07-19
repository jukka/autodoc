(ns com.infolace.gen-docs.build-html
  (:refer-clojure :exclude [empty complement]) 
  (:use net.cgrand.enlive-html
        com.infolace.gen-docs.params
        [clojure.contrib.pprint :only (cl-format)]
        [clojure.contrib.pprint.utilities :only (prlabel)]
        [clojure.contrib.duck-streams :only (with-out-writer)]
        [com.infolace.gen-docs.collect-info :only (contrib-info)]))

(def *output-directory* (str *file-prefix* "wiki-src/"))

(def *layout-file* "layout.html")
(def *master-toc-file* "master-toc.html")
(def *local-toc-file* "local-toc.html")

(def *overview-file* "overview.html")
(def *namespace-api-file* "namespace-api.html")
(def *sub-namespace-api-file* "sub-namespace-api.html")
(def *index-html-file* "api-index.html")

(defn template-for
  "Get the actual filename corresponding to a template"
  [base] 
  (str "templates/" base))

(defn get-template 
  "Get the html node corresponding to this template file"
  [base]
  (first (html-resource (template-for base))))

(defn content-nodes 
  "Strip off the <html><body>  ... </body></html> brackets that tag soup will add to
partial html data leaving a vector of nodes which we then wrap in a <div> tag"
  [nodes]
  {:tag :div, :content (:content (first (:content (first nodes))))})

(defmacro deffragment [name template-file args & body]
  `(defn ~name ~args
     (content-nodes
      (at (get-template ~template-file)
        ~@body))))

(defmacro with-template [template-file & body]
  `(content-nodes
    (at (get-template ~template-file)
      ~@body)))

(deftemplate page (template-for *layout-file*)
  [title master-toc local-toc page-content]
  [:html :head :title] (content title)
  [:div#leftcolumn] (content master-toc)
  [:div#right-sidebar] (content local-toc)
  [:div#content-tag] (content page-content))

(defn create-page [output-file title master-toc local-toc page-content]
  (with-out-writer (str *output-directory* output-file) 
    (print
     (apply str (page title master-toc local-toc page-content)))))

(defn ns-html-file [ns-info]
  (str (:short-name ns-info) "-api.html"))

(defn overview-toc-data 
  [ns-info]
  (for [ns ns-info] [(:short-name ns) (:short-name ns)]))

(defn var-tag-name [ns v] (str (:short-name ns) "/" (:name v)))

(defn var-toc-entries 
  "Build the var-name, <a> tag pairs for the vars in ns"
  [ns]
  (for [v (:members ns)] [(:name v) (var-tag-name ns v)]))

(defn ns-toc-data [ns]
  (apply 
   vector 
   ["Overview" "toc0" (var-toc-entries ns)]
   (for [sub-ns (:subspaces ns)]
     [(:short-name sub-ns) (:short-name sub-ns) (var-toc-entries sub-ns)])))

(defn add-ns-vars [base-ns ns]
  (clone-for [v (:members ns)]
             #(at % 
                [:a] (do->
                      (set-attr :href
                                (str (ns-html-file base-ns) "#" (var-tag-name ns v)))
                      (content (:name v))))))

(defn process-see-also
  "Take the variations on the see-also metadata and turn them into a canonical [link text] form"
  [see-also-seq]
  (map 
   #(cond
      (string? %) [% %] 
      (< (count %) 2) (repeat 2 %)
      :else %) 
   see-also-seq))

(defn see-also-links [ns]
  (if-let [see-also (seq (:see-also ns))]
    #(at %
       [:span#see-also-link] 
       (clone-for [[link text] (process-see-also (:see-also ns))]
         (fn [t] 
           (at t
             [:a] (do->
                   (set-attr :href link)
                   (content text))))))))

(defn namespace-overview [ns template]
  (at template
    [:#namespace-tag] 
    (do->
     (set-attr :id (:short-name ns))
     (content (:short-name ns)))
    [:#author] (content (or (:author ns) "unknown author"))
    [:a#api-link] (set-attr :href (ns-html-file ns))
    [:pre#namespace-docstr] (content (:doc ns))
    [:span#var-link] (add-ns-vars ns ns)
    [:span#subspace] (if-let [subspaces (seq (:subspaces ns))]
                       (clone-for [s subspaces]
                         #(at % 
                            [:span#name] (content (:short-name s))
                            [:span#sub-var-link] (add-ns-vars ns s))))
    [:span#see-also] (see-also-links ns)))

(deffragment make-overview-content *overview-file* [ns-info]
  [:div#namespace-entry] (clone-for [ns ns-info] #(namespace-overview ns %)))

(deffragment make-master-toc *master-toc-file* [ns-info]
  [:ul#left-sidebar-list :li] (clone-for [ns ns-info]
                                #(at %
                                   [:a] (do->
                                         (set-attr :href (ns-html-file ns))
                                         (content (:short-name ns))))))

(deffragment make-local-toc *local-toc-file* [toc-data]
  [:.toc-section] (clone-for [[text tag entries] toc-data]
                    #(at %
                       [:a] (do->
                             (set-attr :href (str "#" tag))
                             (content text))
                       [:.toc-entry] (clone-for [[subtext subtag] entries]
                                       (fn [node]
                                         (at node
                                           [:a] (do->
                                                 (set-attr :href (str "#" subtag))
                                                 (content subtext))))))))

(defn make-overview [ns-info master-toc]
  (create-page *overview-file*
               "Clojure Contrib - Overview"
               master-toc
               (make-local-toc (overview-toc-data ns-info))
               (make-overview-content ns-info)))

;;; TODO: redo this so the usage parts can be styled
(defn var-usage [v]
  (if-let [arglists (:arglists v)]
    (cl-format nil
               "~<Usage: ~:i~@{~{(~a~{ ~a~})~}~^~:@_~}~:>~%"
               (map #(vector %1 %2) (repeat (:name v)) arglists))
    (if (= (:var-type v) "multimethod")
      "No usage documentation available")))

(defn var-src-link [v]
  (when (and (:file v) (:line v))
    (cl-format nil "~a/~a#L~d" *web-src-dir* (:file v) (:line v))))

;;; TODO: factor out var from namespace and sub-namespace into a separate template.
(defn var-details [ns v template]
  (at template 
    [:#var-tag] 
    (do->
     (set-attr :id (var-tag-name ns v))
     (content (:name v)))
    [:span#var-type] (content (:var-type v))
    [:pre#var-usage] (content (var-usage v))
    [:pre#var-docstr] (content (:doc v))
    [:a#var-source] (set-attr :href (var-src-link v))))

(declare render-namespace-api)

(defn make-ns-content [ns]
  (render-namespace-api *namespace-api-file* ns))

(defn render-namespace-api [template-file ns]
  (with-template template-file
    [:#namespace-name] (content (:short-name ns))
    [:span#author] (content (or (:author ns) "Unknown"))
    [:span#long-name] (content (:full-name ns))
    [:pre#namespace-docstr] (content (:doc ns))
    [:span#see-also] (see-also-links ns)
    [:div#var-entry] (clone-for [v (:members ns)] #(var-details ns v %))
    [:div#sub-namespaces]
    (clone-for [sub-ns (:subspaces ns)]
      (fn [_] (render-namespace-api *sub-namespace-api-file* sub-ns)))))

(defn make-ns-page [ns master-toc]
  (create-page (ns-html-file ns)
               (str "clojure contrib - " (:short-name ns) " API reference")
               master-toc
               (make-local-toc (ns-toc-data ns))
               (make-ns-content ns)))

(defn vars-by-letter 
  "Produce a lazy seq of two-vectors containing the letters A-Z and Other with all the 
vars in ns-info that begin with that letter"
  [ns-info]
  (let [chars (conj (into [] (map #(str (char (+ 65 %))) (range 26))) "Other")
        var-map (apply merge-with conj 
                       (into {} (for [c chars] [c [] ]))
                       (for [v (mapcat #(for [v (:members %)] [v %]) ns-info)]
                         {(or (re-find #"[A-Z]" (-> v first :name .toUpperCase))
                              "Other")
                          v}))]
    (for [c chars] [c (sort-by #(-> % first :name .toUpperCase) (get var-map c))])))

(defn doc-prefix [v n]
  "Get a prefix of the doc string suitable for use in an index"
  (let [doc (:doc v)
        len (min (count doc) n)
        suffix (if (< len (count doc)) "..." ".")]
    (str (.replaceAll (.substring doc 0 len) "\n *" " ") suffix)))

(defn gen-index-line [v ns]
  (let [var-name (:name v)
        overhead (count var-name)
        short-name (:short-name ns)
        doc-len (+ 50 (min 0 (- 18 (count short-name))))]
    #(at %
       [:a] (do->
             (set-attr :href (str (ns-html-file ns) "#" (:name v)))
             (content (:name v)))
       [:#line-content] (content 
                        (cl-format nil "~vt~a~vt~a~vt~a~%"
                                   (- 29 overhead)
                                   (:var-type v) (- 43 overhead)
                                   short-name (- 62 overhead)
                                   (doc-prefix v doc-len))))))

;; TODO: skip entries for letters with no members
(deffragment make-index-content *index-html-file* [vars-by-letter]
  [:div#index-body] (clone-for [[letter vars] vars-by-letter]
                      #(at %
                         [:span#section-head] (content letter)
                         [:span#section-content] (clone-for [[v ns] vars]
                                                   (gen-index-line v ns)))))

(defn make-index-html [ns-info master-toc]
  (create-page *index-html-file*
               "Clojure Contrib - Index"
               master-toc
               nil
               (make-index-content (vars-by-letter ns-info))))


(defn make-all-pages []
  (let [ns-info (contrib-info)
        master-toc (make-master-toc ns-info)]
    (make-overview ns-info master-toc)
    (doseq [ns ns-info]
      (make-ns-page ns master-toc))
    (make-index-html ns-info master-toc)
    ;;TODO: add json index page
    ))
