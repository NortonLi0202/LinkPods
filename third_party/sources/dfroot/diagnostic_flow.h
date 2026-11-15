#ifndef DIAGNOSTIC_FLOW_H
#define DIAGNOSTIC_FLOW_H

struct df_diag_ops {
    int (*check_environment)(void *);
    int (*capture_start)(void *);
    int (*load_candidate)(void *);
    int (*start_late_load)(void *);
    int (*capture_finish)(void *);
};

int df_diag_run(const struct df_diag_ops *ops, void *opaque);

#endif
