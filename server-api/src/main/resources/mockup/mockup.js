// --- Data Model for Tasks (Seed Data) ---
    const taskData = {
      1: { id: "TASK-01 · v2", title: "Build the exhibit stand", desc: "Assemble the structural timber base and test physical stability before mounting presentation panels.", status: "To Do", points: 8, category: "Build", due: "2026-10-09", assignees: [{name: "Bea", status: "Signed (Oct 2)", signed: true}, {name: "Mika", status: "Pending Acceptance", signed: false}] },
      2: { id: "TASK-02 · v1", title: "Draft exhibit labels", desc: "Write clear, readable labels and placards for each physical component of the exhibit.", status: "To Do", points: 3, category: "Writing", due: "2026-10-10", assignees: [{name: "Nico", status: "Signed (Oct 1)", signed: true}] },
      3: { id: "TASK-03 · v1", title: "Prepare the slide deck", desc: "Prepare cohesive slide presentation covering the research findings and interactive exhibit elements.", status: "In Progress", points: 6, category: "Presentation", due: "2026-10-08", assignees: [{name: "Alex", status: "Signed", signed: true}, {name: "Nico", status: "Signed", signed: true}] },
      4: { id: "TASK-04 · v1", title: "Test the prototype", desc: "Check interactive sensor walkthrough, document test cases, and log issues for leader review.", status: "Review", points: 5, category: "Testing", due: "2026-10-07", assignees: [{name: "Mika", status: "Signed", signed: true}] },
      5: { id: "TASK-05 · v1", title: "Research the topic", desc: "Collect and synthesize academic source material on interactive museum exhibits.", status: "Done", points: 4, category: "Research", due: "—", assignees: [{name: "Alex", status: "Approved", signed: true}, {name: "Bea", status: "Approved", signed: true}] },
      6: { id: "TASK-06 · v1", title: "Sketch the exhibit layout", desc: "Design floorplan and visual pathway for campus exhibit attendees.", status: "Done", points: 4, category: "Planning", due: "—", assignees: [{name: "Nico", status: "Approved", signed: true}] },
      7: { id: "TASK-07 · v1", title: "Prepare the demo walkthrough", desc: "Rehearse and script the 5-minute live walkthrough for faculty presentation day.", status: "In Progress", points: 4, category: "Presentation", due: "2026-10-11", assignees: [{name: "Mika", status: "Signed", signed: true}] },
      8: { id: "TASK-08 · v1", title: "Print an extra poster", desc: "An extra poster is no longer needed; use the display labels instead.", status: "Cancelled", points: 2, category: "Printing", due: "2026-10-08", assignees: [{name: "Bea", status: "Archived", signed: false}] }
    };

    // --- View Routing System ---
    const pages = {
      'view-table': { crumb: '· Tasks Table', tbBtn: 'tb-table-btn', navBtn: 'nav-all-tasks' },
      'view-board': { crumb: '· Kanban Board', tbBtn: 'tb-board-btn', navBtn: 'nav-board' },
      'view-review': { crumb: '· Leader Review Queue', tbBtn: null, navBtn: 'nav-review' },
      'view-share': { crumb: '· Points & Workload', tbBtn: 'tb-share-btn', navBtn: 'nav-share' },
      'view-activity': { crumb: '· Action Log (What Happened)', tbBtn: null, navBtn: 'nav-activity' },
      'view-report': { crumb: '· Professor Report (.xlsx)', tbBtn: null, navBtn: 'nav-report' }
    };

    function navigateToView(viewId) {
      document.querySelectorAll('.view-page').forEach(page => page.classList.remove('active'));
      const targetPage = document.getElementById(viewId);
      if (targetPage) targetPage.classList.add('active');

      // Update Toolbar Segmented state
      document.querySelectorAll('.segmented-btn').forEach(btn => btn.classList.remove('active'));
      const activeTbBtn = document.getElementById(pages[viewId]?.tbBtn);
      if (activeTbBtn) activeTbBtn.classList.add('active');

      // Update Sidebar Nav state
      document.querySelectorAll('.sidebar-item').forEach(item => item.classList.remove('active'));
      const activeNavBtn = document.getElementById(pages[viewId]?.navBtn);
      if (activeNavBtn) activeNavBtn.classList.add('active');

      // Update Crumb
      document.getElementById('nav-view-crumb').textContent = pages[viewId]?.crumb || '';
    }

    // Attach Sidebar and Toolbar click listeners
    document.querySelectorAll('[data-nav-target]').forEach(link => {
      link.addEventListener('click', (e) => {
        e.preventDefault();
        navigateToView(link.getAttribute('data-nav-target'));
      });
    });

    document.querySelectorAll('.segmented-btn[data-target-view]').forEach(btn => {
      btn.addEventListener('click', () => {
        navigateToView(btn.getAttribute('data-target-view'));
      });
    });

    // --- Task Inspector Selection ---
    function selectTask(taskId) {
      const task = taskData[taskId];
      if (!task) return;

      document.getElementById('insp-task-id').textContent = task.id;
      document.getElementById('insp-task-title').textContent = task.title;
      document.getElementById('insp-task-desc').textContent = task.desc;
      document.getElementById('task-status-select').value = task.status;
      document.getElementById('task-effort-input').value = task.points;
      document.getElementById('insp-task-category').value = task.category;
      document.getElementById('insp-task-due').value = task.due;

      // Update acceptance box
      const box = document.getElementById('insp-acceptance-box');
      box.innerHTML = task.assignees.map(a => `
        <div class="acceptance-row">
          <div class="acceptance-user">
            <span class="avatar-bubble avatar-${a.name.toLowerCase()}" style="width: 18px; height: 18px; font-size: 9px;">${a.name[0]}</span>
            <span>${a.name}</span>
          </div>
          <span class="acceptance-status-tag ${a.signed ? 'signed' : 'waiting'}">
            <svg width="12" height="12" viewBox="0 0 24 24" fill="currentColor">${a.signed ? '<path d="M9 16.17L4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41z"/>' : '<path d="M11.99 2C6.47 2 2 6.48 2 12s4.47 10 9.99 10C17.52 22 22 17.52 22 12S17.52 2 11.99 2zM12 20c-4.42 0-8-3.58-8-8s3.58-8 8-8 8 3.58 8 8-3.58 8-8 8zm.5-13H11v6l5.25 3.15.75-1.23-4.5-2.67z"/>'}</svg>
            ${a.status}
          </span>
        </div>
      `).join('');

      // Update row selection highlights
      document.querySelectorAll('.task-table tbody tr').forEach(r => r.classList.remove('selected'));
      const activeRow = document.querySelector(`.task-table tr[data-task-id="${taskId}"]`);
      if (activeRow) activeRow.classList.add('selected');

      document.querySelectorAll('.kanban-card').forEach(c => c.classList.remove('selected'));
      const activeCard = document.querySelector(`.kanban-card[data-task-id="${taskId}"]`);
      if (activeCard) activeCard.classList.add('selected');
    }

    document.querySelectorAll('[data-task-id]').forEach(el => {
      el.addEventListener('click', () => {
        selectTask(el.getAttribute('data-task-id'));
      });
    });

    let currentSelectedTaskId = 1;
    let toastTimer = null;

    // --- Apple-style Toast Notification HUD ---
    function showToast(message, isSuccess = true) {
      const toast = document.getElementById('apple-toast');
      const text = document.getElementById('toast-text');
      const icon = document.getElementById('toast-icon');
      if (!toast || !text) return;
      text.textContent = message;
      if (icon) icon.style.fill = isSuccess ? 'var(--apple-green)' : 'var(--apple-orange)';
      toast.classList.add('show');
      clearTimeout(toastTimer);
      toastTimer = setTimeout(() => {
        toast.classList.remove('show');
      }, 3000);
    }

    // --- Inspector Smooth Toggle ---
    const inspectorBtn = document.getElementById('toggle-inspector');
    const inspectorClose = document.getElementById('inspector-close-btn');
    const inspectorPanel = document.getElementById('inspector-panel');
    const macWorkspace = document.querySelector('.mac-workspace');
    let inspectorVisible = true;

    function toggleInspector() {
      inspectorVisible = !inspectorVisible;
      if (inspectorVisible) {
        inspectorPanel.classList.remove('collapsed');
        macWorkspace.style.setProperty('--inspector-width', '340px');
      } else {
        inspectorPanel.classList.add('collapsed');
        macWorkspace.style.setProperty('--inspector-width', '0px');
      }
    }
    inspectorBtn?.addEventListener('click', toggleInspector);
    inspectorClose?.addEventListener('click', toggleInspector);

    // --- Window Controls (Traffic Lights) ---
    const macWindow = document.querySelector('.mac-window');
    document.getElementById('dot-zoom')?.addEventListener('click', () => {
      macWindow.classList.toggle('maximized');
      showToast(macWindow.classList.contains('maximized') ? 'Window expanded to full viewport' : 'Restored standard window size');
    });
    document.getElementById('dot-close')?.addEventListener('click', () => {
      showToast('Session active (Alex) · Press ⌘W to close tab', false);
    });
    document.getElementById('dot-min')?.addEventListener('click', () => {
      showToast('Workspace minimized to dock');
    });

    // --- Live Filter & Search Engine ---
    let currentFilter = 'all';
    let currentSearch = '';
    let currentKanbanFilter = 'all';

    function applyFilters() {
      let visibleCount = 0;
      const rows = document.querySelectorAll('.task-table tbody tr');

      rows.forEach(row => {
        const id = row.getAttribute('data-task-id');
        const task = taskData[id];
        if (!task) return;

        // Filter condition
        let matchesFilter = true;
        if (currentFilter === 'active') {
          matchesFilter = (task.status === 'To Do' || task.status === 'In Progress' || task.status === 'Review');
        } else if (currentFilter === 'needs-acceptance') {
          matchesFilter = task.assignees.some(a => !a.signed);
        } else if (currentFilter === 'done') {
          matchesFilter = (task.status === 'Done');
        } else if (currentFilter !== 'all') {
          matchesFilter = (task.status === currentFilter);
        }

        // Search condition
        let matchesSearch = true;
        if (currentSearch) {
          const haystack = `${task.id} ${task.title} ${task.category} ${task.status} ${task.assignees.map(a => a.name).join(' ')}`.toLowerCase();
          matchesSearch = haystack.includes(currentSearch);
        }

        const show = matchesFilter && matchesSearch;
        row.style.display = show ? '' : 'none';
        if (show) visibleCount++;
      });

      // Update count label
      const countLabel = document.getElementById('table-count-label');
      if (countLabel) {
        if (currentSearch) {
          countLabel.textContent = `${visibleCount} tasks matching "${currentSearch}"`;
        } else if (currentFilter !== 'all') {
          countLabel.textContent = `Showing ${visibleCount} filtered tasks`;
        } else {
          countLabel.textContent = `8 tasks · 36 total effort points`;
        }
      }

      // Filter Kanban cards
      document.querySelectorAll('.kanban-card').forEach(card => {
        const id = card.getAttribute('data-task-id');
        const task = taskData[id];
        if (!task) return;

        let matchesCardSearch = true;
        if (currentSearch) {
          const haystack = `${task.id} ${task.title} ${task.category} ${task.status} ${task.assignees.map(a => a.name).join(' ')}`.toLowerCase();
          matchesCardSearch = haystack.includes(currentSearch);
        }

        let matchesKanban = true;
        if (currentKanbanFilter === 'my') {
          matchesKanban = task.assignees.some(a => a.name === 'Alex');
        }

        card.style.display = (matchesCardSearch && matchesKanban) ? '' : 'none';
      });
    }

    // Attach Search Input
    const searchInput = document.getElementById('task-search-input');
    searchInput?.addEventListener('input', (e) => {
      currentSearch = e.target.value.trim().toLowerCase();
      applyFilters();
    });

    // Attach Table Filter Chips
    document.querySelectorAll('.filter-chip[data-filter]').forEach(chip => {
      chip.addEventListener('click', () => {
        document.querySelectorAll('.filter-chip[data-filter]').forEach(c => c.classList.remove('active'));
        chip.classList.add('active');
        currentFilter = chip.getAttribute('data-filter');
        document.querySelectorAll('[data-status-filter]').forEach(s => s.classList.remove('active'));
        applyFilters();
      });
    });

    // Attach Kanban Filter Chips
    document.querySelectorAll('.filter-chip[data-kanban-filter]').forEach(chip => {
      chip.addEventListener('click', () => {
        document.querySelectorAll('.filter-chip[data-kanban-filter]').forEach(c => c.classList.remove('active'));
        chip.classList.add('active');
        currentKanbanFilter = chip.getAttribute('data-kanban-filter');
        applyFilters();
      });
    });

    // Attach Sidebar Status Filters
    document.querySelectorAll('[data-status-filter]').forEach(item => {
      item.addEventListener('click', (e) => {
        e.preventDefault();
        const status = item.getAttribute('data-status-filter');
        navigateToView('view-table');
        document.querySelectorAll('[data-status-filter]').forEach(s => s.classList.remove('active'));
        item.classList.add('active');
        currentFilter = status;
        document.querySelectorAll('.filter-chip[data-filter]').forEach(c => c.classList.remove('active'));
        applyFilters();
      });
    });

    // Keyboard Shortcuts (Escape to dismiss, ⌘F to search)
    window.addEventListener('keydown', (e) => {
      if (e.key === 'Escape') {
        document.querySelectorAll('.apple-modal-overlay.open').forEach(m => m.classList.remove('open'));
        if (currentSearch) {
          currentSearch = '';
          if (searchInput) searchInput.value = '';
          applyFilters();
        }
      } else if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'f') {
        e.preventDefault();
        searchInput?.focus();
        searchInput?.select();
      }
    });

    // --- Theme Toggle ---
    const themeBtn = document.getElementById('theme-toggle');
    const themeLabel = document.getElementById('theme-label');
    const html = document.documentElement;

    themeBtn?.addEventListener('click', () => {
      const isDark = html.getAttribute('data-theme') === 'dark';
      const next = isDark ? 'light' : 'dark';
      html.setAttribute('data-theme', next);
      if (themeLabel) themeLabel.textContent = isDark ? 'Switch to Dark Mode' : 'Switch to Light Mode';
      showToast(isDark ? 'Switched to Light Mode' : 'Switched to Dark Mode');
    });

    // --- Modals / Sheets Interaction ---
    const reportModal = document.getElementById('report-modal-overlay');
    document.getElementById('export-report-btn')?.addEventListener('click', () => reportModal.classList.add('open'));
    document.getElementById('analytics-export-btn')?.addEventListener('click', () => reportModal.classList.add('open'));
    document.getElementById('close-report-modal')?.addEventListener('click', () => reportModal.classList.remove('open'));
    document.getElementById('cancel-report-btn')?.addEventListener('click', () => reportModal.classList.remove('open'));
    document.getElementById('confirm-download-btn')?.addEventListener('click', () => {
      reportModal.classList.remove('open');
      showToast('Generated owlibi-campus-exhibit-20261015T114500Z.xlsx (4 sheets)');
    });

    const agreementModal = document.getElementById('agreement-modal-overlay');
    document.getElementById('open-agreement-diff-btn')?.addEventListener('click', () => agreementModal.classList.add('open'));
    document.getElementById('close-agreement-modal')?.addEventListener('click', () => agreementModal.classList.remove('open'));
    document.getElementById('decline-agreement-btn')?.addEventListener('click', () => {
      agreementModal.classList.remove('open');
      showToast('Revision request sent to leader (Alex)', false);
    });

    // --- Reactive Agreement Signing ---
    document.getElementById('sign-agreement-btn')?.addEventListener('click', () => {
      taskData[1].assignees[1].signed = true;
      taskData[1].assignees[1].status = 'Signed (Just now)';

      agreementModal.classList.remove('open');

      const banner = document.querySelector('.apple-banner');
      if (banner) {
        banner.style.transition = 'opacity 200ms ease, transform 200ms ease';
        banner.style.opacity = '0';
        banner.style.transform = 'translateY(-8px)';
        setTimeout(() => banner.style.display = 'none', 200);
      }

      const row1 = document.querySelector('.task-table tr[data-task-id="1"]');
      if (row1) {
        const agreeCell = row1.querySelector('.agreement-state');
        if (agreeCell) {
          agreeCell.className = 'agreement-state active';
          agreeCell.innerHTML = '<svg width="12" height="12" viewBox="0 0 24 24" fill="currentColor"><path d="M9 16.17L4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41z"/></svg> v2 · Active (2/2)';
        }
      }

      selectTask(1);

      const timeline = document.querySelector('.activity-timeline');
      if (timeline) {
        const newEntry = document.createElement('div');
        newEntry.className = 'activity-entry';
        newEntry.innerHTML = `
          <span class="activity-dot green"></span>
          <div class="activity-text">
            <strong>Mika</strong> accepted version 2 of <em>"Build the exhibit stand"</em>. Progression is now active.
          </div>
          <span class="activity-time">Just now · Asia/Manila</span>
        `;
        timeline.insertBefore(newEntry, timeline.firstChild);
      }

      showToast('Agreement v2 signed by Mika. Progression is now active!');
    });

    document.getElementById('banner-view-log-btn')?.addEventListener('click', () => {
      navigateToView('view-activity');
    });

    // Toolbar Export Button -> Navigate to Spreadsheet View
    document.getElementById('tb-export-report-btn')?.addEventListener('click', () => navigateToView('view-report'));

    document.getElementById('view-report-dl-btn')?.addEventListener('click', () => {
      showToast('Downloading owlibi-campus-exhibit-20261015T114500Z.xlsx');
    });

    // --- Reactive Review Queue Actions ---
    document.getElementById('rq-approve-btn')?.addEventListener('click', () => {
      taskData[4].status = 'Done';

      // Update table row 4
      const row4 = document.querySelector('.task-table tr[data-task-id="4"]');
      if (row4) {
        row4.classList.add('done');
        const check = row4.querySelector('.task-checkbox');
        if (check) check.classList.add('checked');
        const statusCell = row4.querySelector('.status-pill');
        if (statusCell) {
          statusCell.className = 'status-pill status-done';
          statusCell.innerHTML = '<span class="status-dot"></span> Done';
        }
      }

      // Move kanban card
      const card4 = document.querySelector('.kanban-card[data-task-id="4"]');
      const doneCol = document.querySelectorAll('.kanban-column')[3];
      if (card4 && doneCol) {
        const doneList = doneCol.querySelector('.kanban-card-list');
        doneList.appendChild(card4);
        card4.style.borderLeft = '';
        const titleEl = card4.querySelector('.kanban-card-title');
        if (titleEl) {
          titleEl.style.textDecoration = 'line-through';
          titleEl.style.color = 'var(--label-tertiary)';
        }
        const metaDue = card4.querySelector('.kanban-due-date');
        if (metaDue) {
          metaDue.style.color = 'var(--apple-green)';
          metaDue.textContent = 'Approved';
        }
        const reviewCol = document.querySelectorAll('.kanban-column')[2];
        if (reviewCol) {
          const count = reviewCol.querySelector('.kanban-column-count');
          if (count) count.textContent = '0';
        }
        const doneCount = doneCol.querySelector('.kanban-column-count');
        if (doneCount) doneCount.textContent = '3';
      }

      // Update Review Queue view
      const rqCard = document.querySelector('.review-queue-card');
      if (rqCard) {
        rqCard.innerHTML = `
          <div style="text-align: center; padding: 48px 20px; color: var(--label-tertiary);">
            <svg width="40" height="40" viewBox="0 0 24 24" fill="currentColor" style="color: var(--apple-green); margin-bottom: 12px;"><path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm-2 15l-5-5 1.41-1.41L10 14.17l7.59-7.59L19 8l-9 9z"/></svg>
            <div style="font-size: 16px; font-weight: 650; color: var(--label-primary);">Review Queue is Clear</div>
            <div style="font-size: 12.5px; margin-top: 6px; color: var(--label-secondary);">"Test the prototype" approved. 5.0 completed points credited to Mika.</div>
          </div>
        `;
      }

      // Update sidebar badges
      const revBadge = document.getElementById('sidebar-review-badge');
      if (revBadge) {
        revBadge.textContent = '0';
        revBadge.classList.remove('alert');
      }
      const doneBadge = document.getElementById('badge-done');
      if (doneBadge) doneBadge.textContent = '3';
      const reviewFilterBadge = document.getElementById('badge-review');
      if (reviewFilterBadge) {
        reviewFilterBadge.textContent = '0';
        reviewFilterBadge.classList.remove('alert');
      }

      // Recalculate Points Share (New Total = 13 pts)
      document.querySelectorAll('.total-points-big').forEach(el => el.textContent = '13.0 pts');
      document.querySelectorAll('.proportional-bar').forEach(bar => {
        bar.innerHTML = `
          <div class="bar-segment bar-mika" style="width: 38.5%;" title="Mika: 38.5%"></div>
          <div class="bar-segment bar-nico" style="width: 30.8%;" title="Nico: 30.8%"></div>
          <div class="bar-segment bar-alex" style="width: 15.4%;" title="Alex: 15.4%"></div>
          <div class="bar-segment bar-bea" style="width: 15.4%;" title="Bea: 15.4%"></div>
        `;
      });

      const memberItems = document.querySelectorAll('.member-share-item');
      if (memberItems.length >= 4) {
        memberItems[0].querySelector('.member-share-pct').textContent = '30.8%';
        memberItems[1].querySelector('.member-share-pct').textContent = '15.4%';
        memberItems[2].querySelector('.member-share-pct').textContent = '15.4%';

        const mikaCard = memberItems[3];
        mikaCard.querySelector('.member-share-pct').textContent = '38.5%';
        mikaCard.querySelector('.member-share-pct').style.color = 'var(--label-primary)';
        mikaCard.querySelectorAll('.member-pts-detail')[0].innerHTML = '<span>Completed effort:</span><strong>5.0 pts</strong>';
        mikaCard.querySelectorAll('.member-pts-detail')[1].innerHTML = '<span>1 task finished</span><span>(Test prototype)</span>';
        mikaCard.querySelector('.member-in-progress-note').textContent = '12 pts active in flight';
        mikaCard.querySelector('.member-in-progress-note').style.color = 'var(--label-secondary)';
      }

      // Add to Action Log
      const timeline = document.querySelector('.activity-timeline');
      if (timeline) {
        const newEntry = document.createElement('div');
        newEntry.className = 'activity-entry';
        newEntry.innerHTML = `
          <span class="activity-dot green"></span>
          <div class="activity-text">
            <strong>Alex</strong> approved <em>"Test the prototype"</em> as <strong>Done</strong>. 5.0 effort points credited to Mika.
          </div>
          <span class="activity-time">Just now · Asia/Manila</span>
        `;
        timeline.insertBefore(newEntry, timeline.firstChild);
      }

      showToast('Task approved as Done. 5.0 points credited to Mika!');
    });

    document.getElementById('rq-return-btn')?.addEventListener('click', () => {
      showToast('Return with Reason dialog simulated', false);
    });
    document.getElementById('rq-inspect-btn')?.addEventListener('click', () => {
      selectTask(4);
      if (!inspectorVisible) toggleInspector();
    });

    // Inspector Effort & Status Interactivity
    const effortInput = document.getElementById('task-effort-input');
    const effortWarn = document.getElementById('insp-effort-warn');
    effortInput?.addEventListener('input', () => {
      if (effortWarn) effortWarn.style.display = 'block';
    });

    document.getElementById('task-status-select')?.addEventListener('change', (e) => {
      showToast(`Task status updated to "${e.target.value}"`);
    });

    document.getElementById('project-switch-trigger')?.addEventListener('click', () => {
      showToast('Project scope: Campus exhibit (Single active project per D-018)');
    });
    document.getElementById('open-settings-btn')?.addEventListener('click', () => {
      showToast('Project Settings: Asia/Manila · 8.0 pt Threshold · 7-day Near-Due');
    });
    document.getElementById('open-new-task-btn')?.addEventListener('click', () => {
      showToast('New Task draft creates Agreement v1 with pending acceptance');
    });

    // --- Professor Report Spreadsheet Tab Switcher ---
    const sheetTabs = [
      { btn: 'tab-sheet-summary', content: 'sheet-content-summary' },
      { btn: 'tab-sheet-tasks', content: 'sheet-content-tasks' },
      { btn: 'tab-sheet-contrib', content: 'sheet-content-contrib' },
      { btn: 'tab-sheet-audit', content: 'sheet-content-audit' }
    ];
    sheetTabs.forEach(tab => {
      const b = document.getElementById(tab.btn);
      if (b) {
        b.addEventListener('click', () => {
          sheetTabs.forEach(t => {
            document.getElementById(t.btn)?.classList.remove('active');
            const c = document.getElementById(t.content);
            if (c) c.style.display = 'none';
          });
          b.classList.add('active');
          const activeContent = document.getElementById(tab.content);
          if (activeContent) activeContent.style.display = 'flex';
        });
      }
    });
