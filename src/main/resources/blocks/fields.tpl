
#foreach($c in $columns)
#if($c.comment)
    /**
     * $c.comment.replace("\n", " ")
     */
#end
    private ${c.type} ${c.field};

#end
#foreach($c in $columns)
#if($c.dateLike)
    /**
     * ${c.label}开始
     */
    private ${c.type} ${c.field}Begin;

    /**
     * ${c.label}结束
     */
    private ${c.type} ${c.field}End;

#end
#end
