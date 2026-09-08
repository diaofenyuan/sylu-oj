package main

type taskClaimer interface {
	ClaimTask(waitSeconds int) (*Task, error)
	ClaimRunTask(waitSeconds int) (*RunTask, error)
}

// 两类队列交替优先，避免持续提交饿死自测；只在另一队列为空时短暂长轮询。
// 不预领下一份任务，防止任务在本机等待期间耗尽租约。
type workClaimer struct {
	client    taskClaimer
	preferRun bool
}

func (c *workClaimer) claim() (*Task, *RunTask, error) {
	if c.preferRun {
		run, err := c.client.ClaimRunTask(0)
		if err != nil {
			return nil, nil, err
		}
		if run != nil {
			c.preferRun = false
			return nil, run, nil
		}
		task, err := c.client.ClaimTask(1)
		return task, nil, err
	}
	task, err := c.client.ClaimTask(0)
	if err != nil {
		return nil, nil, err
	}
	if task != nil {
		c.preferRun = true
		return task, nil, nil
	}
	run, err := c.client.ClaimRunTask(1)
	return nil, run, err
}
