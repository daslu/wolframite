; ***** BEGIN LICENSE BLOCK *****
; Version: MPL 1.1/GPL 2.0/LGPL 2.1
;
; The contents of this file are subject to the Mozilla Public License Version
; 1.1 (the "License"); you may not use this file except in compliance with
; the License. You may obtain a copy of the License at
; http://www.mozilla.org/MPL/
;
; Software distributed under the License is distributed on an "AS IS" basis,
; WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
; for the specific language governing rights and limitations under the
; License.
;
; The Original Code is the Clojure-Mathematica interface library Clojuratica.
;
; The Initial Developer of the Original Code is Garth Sheldon-Coulson.
; Portions created by the Initial Developer are Copyright (C) 2009
; the Initial Developer. All Rights Reserved.
;
; Contributor(s):
;
; Alternatively, the contents of this file may be used under the terms of
; either the GNU General Public License Version 2 or later (the "GPL"), or
; the GNU Lesser General Public License Version 2.1 or later (the "LGPL"),
; in which case the provisions of the GPL or the LGPL are applicable instead
; of those above. If you wish to allow use of your version of this file only
; under the terms of either the GPL or the LGPL, and not to allow others to
; use your version of this file under the terms of the MPL, indicate your
; decision by deleting the provisions above and replace them with the notice
; and other provisions required by the GPL or the LGPL. If you do not delete
; the provisions above, a recipient may use your version of this file under
; the terms of any one of the MPL, the GPL or the LGPL.
;
; ***** END LICENSE BLOCK *****


(ns clojuratica.core
  (:import [clojuratica CExpr]
           [com.wolfram.jlink Expr])
  (:use [clojuratica.lib]))

(declare string-to-expr build-set-expr)

(defn convert
  "Converts any Java object, including any Clojure data structure, to a CExpr. Sequential objects
  are converted to Mathematica lists. See the CExpr class documentation for more information."
  [obj]
  (CExpr. obj))

(defn- common-dispatch
  "Dispatches to the appropriate method. Used by the following multimethods: express, send-read,
  and parse."
  [expression & [kernel-link]]
  (cond (string? expression)          :string
        (instance? Expr expression)   :expr
        (instance? CExpr expression)  :cexpr
        (nil? expression)             :nil
        true  (throw
                (Exception. (str "Expression in common-dispatch must be "
                                 "string, Expr, CExpr, or nil. Passed an object "
                                 "of class " (class expression))))))

(defmulti express common-dispatch)

(defmethod express :string [s kernel-link]
  ; Takes a string and a KernelLink instance. Treats s as a Mathematica expression and converts it to
  ; a CExpr object using the kernel at the other end of kernel-link. Does not evaluate s, but merely
  ; converts it to a CExpr object.
  (CExpr. (string-to-expr s kernel-link)))

(defmethod express :expr [expr & [kernel-link]]
  ; Takes an Expr instance and an optional (unused) KernelLink instance. Converts expr to a CExpr.
  (CExpr. expr))

(defmethod express :cexpr [cexpr & [kernel-link]]
  ; Takes a CExpr instance and an optional (unused) KernelLink instance. Simply returns cexpr.
  cexpr)

(defmethod express :nil [& args]
  ; Express returns nil if passed nil.
  nil)

; Send-read

(defmulti send-read
  "Evaluates the first argument as an expression using the KernelLink instance provided in the
  second argument. First argument can be a string, an Expr, or a CExpr."
  common-dispatch)

(defmethod send-read :string [s kernel-link]
  ; Takes a string and a KernelLink instance. Treats s as a Mathematica expression and evaluates it
  ; using the kernel at the other end of kernel-link. Returns a CExpr containing the output.
  (locking kernel-link
    ;(println ">" s)
    (.evaluate kernel-link s)
    (.waitForAnswer kernel-link)
    (let [output (.. kernel-link getExpr)]
      ;(println output)
      (express output))))

(defmethod send-read :expr [expr kernel-link]
  ; Takes an Expr instance and a KernelLink instance. Evaluates expr using the kernel at the other
  ; end of kernel-link. Returns a CExpr containing the output.
  (locking kernel-link
    ;(println ">" expr)
    (.evaluate kernel-link expr)
    (.waitForAnswer kernel-link)
    (let [output (.. kernel-link getExpr)]
      ;(println output)
      (express output))))

(defmethod send-read :cexpr [cexpr kernel-link]
  ; Takes a CExpr instance and a KernelLink instance. Evaluates cexpr using the kernel at the other
  ; end of kernel-link. Returns a CExpr containing the output.
  (send-read (.getExpr cexpr) kernel-link))

(defmethod send-read :nil [& args]
  ; Send-read returns nil if passed nil.
  nil)

; Parse

(defmulti parse
  "Turns the first argument into a CExpr using the KernelLink instance provided in the
  second argument. First argument can be a string, an Expr, or a CExpr. Second argument
  is required only if the first argument is a string. Otherwise, second argument is optional."
  common-dispatch)

(defmethod parse :string [s kernel-link]
  ; Takes a string and a KernelLink instance. Converts s to a CExpr using kernel-link, then parses the
  ; resulting CExpr into a Clojure object. Returns this Clojure object. For details on how CExprs
  ; are parsed see the documentation for the CExpr class.
  (if (nil? kernel-link)
    (throw (Exception. "When first argument to parse is a string, second argument must be a KernelLink object.")))
  (.parse (express s kernel-link)))

(defmethod parse :expr [expr & [kernel-link]]
  (.parse (express expr)))

(defmethod parse :cexpr [cexpr & [kernel-link]]
  (.parse cexpr))

(defmethod parse :nil [& args]
  nil)

(defn add-head
  "Creates an Expr with head argument as its head and with exprs as its arguments. exprs must be Expr
  instances."
  [head & exprs]
  (let [loop (com.wolfram.jlink.MathLinkFactory/createLoopbackLink)]
    (.putFunction loop head (count exprs))
    (dorun
      (for [expr exprs] (.put loop expr)))
    (.endPacket loop)
    (.getExpr loop)))

; Set-evaluate

(defn build-module
  "Creates an Expr containing a Mathematica module. The syntax of build-module is similar to that
  of Clojure's let. The Mathematica module created will have local (lexically scoped) variables
  as specified in the first argument to build-module, which must be a vector of pairs, as in
  Clojure's let. build-module converts each pair to a Mathematica Set[] expression using build-set-expr.
  The remaining arguments, after the vector, must be expressions. These expressions can be Expr objects,
  CExpr objects, strings, or a combination. Flags may be passed anywhere in the argument list.
  Allowed flags:
  :parallel - Wraps the expressions in a ParallelSubmit[]. This is for use by the functions
              in clojuratica.parallel.
  :serial - Default. Opposite of :parallel.
  :all-output - Packages expressions in a list, such that the output of each expression is returned.
  :last-output - Default. Packages expressions in a CompoundExpression[], such that the output
                 of only the last expression is returned. Opposite of all-output."
  [& args]
  (let [flag-sets      [[:all-output :last-output]
                        [:parallel :serial]]
        flags          (flags args flag-sets)
        args           (remove-flags args)
        set-specs      (first args)
        set-spec-seqs  (partition 2 set-specs)
        set-expr-seq   (for [set-spec-seq set-spec-seqs]
                         (apply build-set-expr set-spec-seq))
        expression-seq (drop-last (rest args))
        kernel-link    (last args)
        cexpr-seq      (for [expression expression-seq]
                         (express expression kernel-link))
        expr-seq       (map (memfn getExpr) cexpr-seq)
        loop           (com.wolfram.jlink.MathLinkFactory/createLoopbackLink)
        compounder     (if (some #{:all-output} flags)
                         "List"
                         "CompoundExpression")]
    (.putFunction loop "Module" 2)
    (.put loop
      (apply add-head (cons "List" set-expr-seq)))
    (when (some #{:parallel} flags)
      (let [local-vars (for [set-expr set-expr-seq] (.part set-expr 1))]
        (.putFunction loop "ParallelSubmit" 2)
        (.put loop
          (apply add-head (cons "List" local-vars)))))
    (.put loop
      (apply add-head (cons compounder expr-seq)))
    (.endPacket loop)
    (.getExpr loop)))

(defn build-set-expr
  "Creates an Expr containing a Mathematica Set[] expression. The righthand side (rhs) of the assignment
  is converted to a CExpr and then an Expr using the convert function."
  [lhs rhs]
  (let [rhs (.getExpr (convert rhs))
        loop (com.wolfram.jlink.MathLinkFactory/createLoopbackLink)]
    (.putFunction loop "Set" 2)
    (.putSymbol loop lhs)
    (.put loop rhs)
    (.endPacket loop)
    (.getExpr loop)))

(defn string-to-expr
  "Converts a string, s, to a Mathematica expression (i.e. an Expr object). Uses the Mathematica
  kernel on the other end of kernel-link to interpret Mathematica syntax."
  [s kernel-link]
  (let [held-s (str "HoldComplete[" s "]")]
    (locking kernel-link
      ;(println "string-to-expr>" held-s)
      (.evaluate kernel-link held-s)
      (.waitForAnswer kernel-link)
      (let [result (.. kernel-link getExpr args)]
        (if (next result)
          (throw (Exception. (str "Invalid expression: " s))))
        (first result)))))

