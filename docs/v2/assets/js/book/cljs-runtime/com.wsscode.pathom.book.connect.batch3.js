goog.provide('com.wsscode.pathom.book.connect.batch3');
goog.require('cljs.core');
goog.require('com.wsscode.pathom.core');
goog.require('com.wsscode.pathom.connect');
goog.require('cljs.core.async');
com.wsscode.pathom.book.connect.batch3.list_things = com.wsscode.pathom.connect.resolver(new cljs.core.Symbol("com.wsscode.pathom.book.connect.batch3","list-things","com.wsscode.pathom.book.connect.batch3/list-things",-1418728916,null),new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword("com.wsscode.pathom.connect","output","com.wsscode.pathom.connect/output",552487303),new cljs.core.PersistentVector(null, 1, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"items","items",1031954938),new cljs.core.PersistentVector(null, 1, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"number","number",1570378438)], null)], null)], null)], null),(function com$wsscode$pathom$book$connect$batch3$list_things(_,___$1){
return new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"items","items",1031954938),new cljs.core.PersistentVector(null, 3, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"number","number",1570378438),(3)], null),new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"number","number",1570378438),(10)], null),new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"number","number",1570378438),(18)], null)], null)], null);
}));
com.wsscode.pathom.book.connect.batch3.slow_resolver = com.wsscode.pathom.connect.resolver(new cljs.core.Symbol("com.wsscode.pathom.book.connect.batch3","slow-resolver","com.wsscode.pathom.book.connect.batch3/slow-resolver",-135470339,null),new cljs.core.PersistentArrayMap(null, 3, [new cljs.core.Keyword("com.wsscode.pathom.connect","input","com.wsscode.pathom.connect/input",-1274439121),new cljs.core.PersistentHashSet(null, new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"number","number",1570378438),null], null), null),new cljs.core.Keyword("com.wsscode.pathom.connect","output","com.wsscode.pathom.connect/output",552487303),new cljs.core.PersistentVector(null, 1, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"number-added","number-added",1161719489)], null),new cljs.core.Keyword("com.wsscode.pathom.connect","transform","com.wsscode.pathom.connect/transform",-953450322),com.wsscode.pathom.connect.transform_batch_resolver], null),(function com$wsscode$pathom$book$connect$batch3$slow_resolver(_,input){
var c__39084__auto__ = cljs.core.async.chan.cljs$core$IFn$_invoke$arity$1((1));
cljs.core.async.impl.dispatch.run(((function (c__39084__auto__){
return (function (){
var f__39085__auto__ = (function (){var switch__38786__auto__ = ((function (c__39084__auto__){
return (function (state_53533){
var state_val_53534 = (state_53533[(1)]);
if((state_val_53534 === (1))){
var inst_53524 = cljs.core.async.timeout((1000));
var state_53533__$1 = state_53533;
return cljs.core.async.impl.ioc_helpers.take_BANG_(state_53533__$1,(2),inst_53524);
} else {
if((state_val_53534 === (2))){
var inst_53526 = (state_53533[(2)]);
var inst_53530 = (function (){return ((function (inst_53526,state_val_53534,c__39084__auto__){
return (function (v){
return new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"number-added","number-added",1161719489),(new cljs.core.Keyword(null,"number","number",1570378438).cljs$core$IFn$_invoke$arity$1(v) + (1))], null);
});
;})(inst_53526,state_val_53534,c__39084__auto__))
})();
var inst_53531 = cljs.core.mapv.cljs$core$IFn$_invoke$arity$2(inst_53530,input);
var state_53533__$1 = (function (){var statearr_53539 = state_53533;
(statearr_53539[(7)] = inst_53526);

return statearr_53539;
})();
return cljs.core.async.impl.ioc_helpers.return_chan(state_53533__$1,inst_53531);
} else {
return null;
}
}
});})(c__39084__auto__))
;
return ((function (switch__38786__auto__,c__39084__auto__){
return (function() {
var com$wsscode$pathom$book$connect$batch3$slow_resolver_$_state_machine__38787__auto__ = null;
var com$wsscode$pathom$book$connect$batch3$slow_resolver_$_state_machine__38787__auto____0 = (function (){
var statearr_53543 = [null,null,null,null,null,null,null,null];
(statearr_53543[(0)] = com$wsscode$pathom$book$connect$batch3$slow_resolver_$_state_machine__38787__auto__);

(statearr_53543[(1)] = (1));

return statearr_53543;
});
var com$wsscode$pathom$book$connect$batch3$slow_resolver_$_state_machine__38787__auto____1 = (function (state_53533){
while(true){
var ret_value__38788__auto__ = (function (){try{while(true){
var result__38789__auto__ = switch__38786__auto__(state_53533);
if(cljs.core.keyword_identical_QMARK_(result__38789__auto__,new cljs.core.Keyword(null,"recur","recur",-437573268))){
continue;
} else {
return result__38789__auto__;
}
break;
}
}catch (e53546){if((e53546 instanceof Object)){
var ex__38790__auto__ = e53546;
var statearr_53547_53643 = state_53533;
(statearr_53547_53643[(5)] = ex__38790__auto__);


cljs.core.async.impl.ioc_helpers.process_exception(state_53533);

return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
throw e53546;

}
}})();
if(cljs.core.keyword_identical_QMARK_(ret_value__38788__auto__,new cljs.core.Keyword(null,"recur","recur",-437573268))){
var G__53644 = state_53533;
state_53533 = G__53644;
continue;
} else {
return ret_value__38788__auto__;
}
break;
}
});
com$wsscode$pathom$book$connect$batch3$slow_resolver_$_state_machine__38787__auto__ = function(state_53533){
switch(arguments.length){
case 0:
return com$wsscode$pathom$book$connect$batch3$slow_resolver_$_state_machine__38787__auto____0.call(this);
case 1:
return com$wsscode$pathom$book$connect$batch3$slow_resolver_$_state_machine__38787__auto____1.call(this,state_53533);
}
throw(new Error('Invalid arity: ' + arguments.length));
};
com$wsscode$pathom$book$connect$batch3$slow_resolver_$_state_machine__38787__auto__.cljs$core$IFn$_invoke$arity$0 = com$wsscode$pathom$book$connect$batch3$slow_resolver_$_state_machine__38787__auto____0;
com$wsscode$pathom$book$connect$batch3$slow_resolver_$_state_machine__38787__auto__.cljs$core$IFn$_invoke$arity$1 = com$wsscode$pathom$book$connect$batch3$slow_resolver_$_state_machine__38787__auto____1;
return com$wsscode$pathom$book$connect$batch3$slow_resolver_$_state_machine__38787__auto__;
})()
;})(switch__38786__auto__,c__39084__auto__))
})();
var state__39086__auto__ = (function (){var statearr_53550 = (f__39085__auto__.cljs$core$IFn$_invoke$arity$0 ? f__39085__auto__.cljs$core$IFn$_invoke$arity$0() : f__39085__auto__.call(null));
(statearr_53550[(6)] = c__39084__auto__);

return statearr_53550;
})();
return cljs.core.async.impl.ioc_helpers.run_state_machine_wrapped(state__39086__auto__);
});})(c__39084__auto__))
);

return c__39084__auto__;
}));
com.wsscode.pathom.book.connect.batch3.app_registry = new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [com.wsscode.pathom.book.connect.batch3.list_things,com.wsscode.pathom.book.connect.batch3.slow_resolver], null);
com.wsscode.pathom.book.connect.batch3.parser = com.wsscode.pathom.core.async_parser(new cljs.core.PersistentArrayMap(null, 3, [new cljs.core.Keyword("com.wsscode.pathom.core","env","com.wsscode.pathom.core/env",602275378),new cljs.core.PersistentArrayMap(null, 2, [new cljs.core.Keyword("com.wsscode.pathom.core","reader","com.wsscode.pathom.core/reader",1510046410),new cljs.core.PersistentVector(null, 3, 5, cljs.core.PersistentVector.EMPTY_NODE, [com.wsscode.pathom.core.map_reader,com.wsscode.pathom.connect.async_reader2,com.wsscode.pathom.connect.open_ident_reader], null),new cljs.core.Keyword("com.wsscode.pathom.core","process-error","com.wsscode.pathom.core/process-error",-2116719411),(function (env,error){
console.error("ERROR",error);

return com.wsscode.pathom.core.error_str(error);
})], null),new cljs.core.Keyword("com.wsscode.pathom.core","mutate","com.wsscode.pathom.core/mutate",-2086097173),com.wsscode.pathom.connect.mutate_async,new cljs.core.Keyword("com.wsscode.pathom.core","plugins","com.wsscode.pathom.core/plugins",-2128476796),new cljs.core.PersistentVector(null, 3, 5, cljs.core.PersistentVector.EMPTY_NODE, [com.wsscode.pathom.connect.connect_plugin.cljs$core$IFn$_invoke$arity$1(new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword("com.wsscode.pathom.connect","register","com.wsscode.pathom.connect/register",-386283650),com.wsscode.pathom.book.connect.batch3.app_registry], null)),com.wsscode.pathom.core.error_handler_plugin,com.wsscode.pathom.core.trace_plugin], null)], null));

//# sourceMappingURL=com.wsscode.pathom.book.connect.batch3.js.map
