package main

import (
	"errors"
	"testing"
)

type fakeTaskClaimer struct {
	task  *Task
	run   *RunTask
	err   error
	calls []string
	waits []int
}

func (f *fakeTaskClaimer) ClaimTask(wait int) (*Task, error) {
	f.calls = append(f.calls, "task")
	f.waits = append(f.waits, wait)
	return f.task, f.err
}

func (f *fakeTaskClaimer) ClaimRunTask(wait int) (*RunTask, error) {
	f.calls = append(f.calls, "run")
	f.waits = append(f.waits, wait)
	return f.run, f.err
}

func TestWorkClaimerAlternatesBusyQueues(t *testing.T) {
	client := &fakeTaskClaimer{task: &Task{}, run: &RunTask{}}
	claimer := &workClaimer{client: client}
	for i := 0; i < 10; i++ {
		task, run, err := claimer.claim()
		if err != nil {
			t.Fatal(err)
		}
		if i%2 == 0 && (task == nil || run != nil) {
			t.Fatal("正式提交未获得调度")
		}
		if i%2 == 1 && (task != nil || run == nil) {
			t.Fatal("自测未获得调度")
		}
	}
	if len(client.calls) != 10 {
		t.Fatalf("不应预领额外任务: %v", client.calls)
	}
	for _, wait := range client.waits {
		if wait != 0 {
			t.Fatal("繁忙队列不应等待")
		}
	}
}

func TestWorkClaimerChecksOtherQueueWithoutLongIdleDelay(t *testing.T) {
	for _, preferRun := range []bool{false, true} {
		client := &fakeTaskClaimer{}
		claimer := &workClaimer{client: client, preferRun: preferRun}
		task, run, err := claimer.claim()
		if err != nil || task != nil || run != nil {
			t.Fatalf("空队列结果错误: %v", err)
		}
		if len(client.waits) != 2 || client.waits[0] != 0 || client.waits[1] != 1 {
			t.Fatalf("空队列应只进行一次短长轮询: %v", client.waits)
		}
		if preferRun {
			client.run = &RunTask{}
		} else {
			client.task = &Task{}
		}
		task, run, err = claimer.claim()
		if err != nil || (task == nil && run == nil) {
			t.Fatal("新任务未及时领取")
		}
		if client.waits[2] != 0 {
			t.Fatal("新任务应立即领取")
		}
	}
}

func TestWorkClaimerUsesAvailableQueue(t *testing.T) {
	for _, preferRun := range []bool{false, true} {
		client := &fakeTaskClaimer{}
		if preferRun {
			client.task = &Task{}
		} else {
			client.run = &RunTask{}
		}
		claimer := &workClaimer{client: client, preferRun: preferRun}
		for i := 0; i < 3; i++ {
			task, run, err := claimer.claim()
			if err != nil || (task == nil && run == nil) {
				t.Fatal("单队列任务不应被跳过")
			}
		}
	}
}

func TestWorkClaimerStopsOnGatewayFailure(t *testing.T) {
	for _, preferRun := range []bool{false, true} {
		want := errors.New("gateway unavailable")
		client := &fakeTaskClaimer{err: want}
		claimer := &workClaimer{client: client, preferRun: preferRun}
		task, run, err := claimer.claim()
		if !errors.Is(err, want) || task != nil || run != nil || len(client.calls) != 1 {
			t.Fatalf("网关故障应交给主循环退避: %v", err)
		}
	}
}
