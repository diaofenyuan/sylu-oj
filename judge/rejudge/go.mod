module sylu-oj/judge/rejudge

go 1.24

require (
	sylu-oj/judge/judgekit v0.0.0
	sylu-oj/judge/sandbox v0.0.0
)

replace sylu-oj/judge/sandbox => ../sandbox

replace sylu-oj/judge/judgekit => ../judgekit
