/* SPDX-License-Identifier: GPL-2.0-only */
#include <errno.h>

#include "diagnostic_flow.h"

int df_diag_run(const struct df_diag_ops *ops, void *opaque)
{
	int ret;
	int load_err;
	int late_err;
	int finish_err;

	if (!ops || !ops->check_environment || !ops->capture_start ||
	    !ops->load_candidate || !ops->start_late_load ||
	    !ops->capture_finish)
		return EINVAL;

	ret = ops->check_environment(opaque);
	if (ret)
		return ret;

	ret = ops->capture_start(opaque);
	if (ret)
		return ret;

	load_err = ops->load_candidate(opaque);
	late_err = 0;
	if (!load_err)
		late_err = ops->start_late_load(opaque);

	finish_err = ops->capture_finish(opaque);

	if (load_err)
		return load_err;
	if (late_err)
		return late_err;
	return finish_err;
}
